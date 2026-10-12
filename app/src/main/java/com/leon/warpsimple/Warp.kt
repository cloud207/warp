package com.leon.warpsimple

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.net.*
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ProfileStore(private val context: Context) {
 private val file get() = java.io.File(context.filesDir, "profile.enc")
 private fun key(): SecretKey {
  val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
  (ks.getKey("warp-profile", null) as? SecretKey)?.let { return it }
  return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
   init(KeyGenParameterSpec.Builder("warp-profile", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
  }.generateKey()
 }
 fun save(profile: String) {
  val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
  val data = c.iv + c.doFinal(profile.toByteArray())
  val atomic = android.util.AtomicFile(file)
  val out = atomic.startWrite()
  try { out.write(data); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out); throw e }
 }
 fun load(): String? {
  if (!file.exists()) return null
  val b = file.readBytes()
  return Cipher.getInstance("AES/GCM/NoPadding").apply {
   init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b.copyOfRange(0, 12)))
  }.doFinal(b.copyOfRange(12, b.size)).toString(Charsets.UTF_8)
 }
}

object Registration {
 suspend fun generate(): String = withContext(Dispatchers.IO) {
  val keys = KeyPair()
  val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
  val payload = JSONObject().put("key", keys.publicKey.toBase64()).put("tos", timestamp)
   .put("fcm_token", "").put("install_id", "").put("serial_number", "").put("locale", "en_US")
   .put("model", android.os.Build.MODEL).put("os_version", "16.0.0").put("key_type", "curve25519").put("tunnel_type", "wireguard")
  val c = URL("https://api.cloudflareclient.com/v0a5641/reg").openConnection() as HttpURLConnection
  try {
   c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 20000; c.doOutput = true
   c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
   c.setRequestProperty("User-Agent", "1.1.1.1/6.38.9-5641 (Android 16.0.0)")
   c.setRequestProperty("CF-Client-Version", "a-6.38.9-5641")
   c.outputStream.use { it.write(payload.toString().toByteArray()) }
   check(c.responseCode in 200..299) { "Registration HTTP ${c.responseCode}. Connect another VPN and retry." }
   val cfg = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONObject("config")
   val addresses = cfg.getJSONObject("interface").getJSONObject("addresses")
   val peer = cfg.getJSONArray("peers").getJSONObject(0)
   val endpoint = peer.getJSONObject("endpoint")
   val host = endpoint.optString("host").ifBlank {
    val ip = endpoint.getString("v4").substringBefore(':')
    "$ip:${endpoint.optJSONArray("ports")?.optInt(0, 2408) ?: 2408}"
   }
   val address = listOf(addresses.optString("v4"), addresses.optString("v6")).filter { it.isNotBlank() }.joinToString(", ")
   val profile = """
[Interface]
PrivateKey = ${keys.privateKey.toBase64()}
Address = $address
DNS = 1.1.1.1, 1.0.0.1
MTU = 1280

[Peer]
PublicKey = ${peer.getString("public_key")}
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = $host
PersistentKeepalive = 25
""".trimIndent()
   com.wireguard.config.Config.parse(profile.byteInputStream())
   profile
  } finally { c.disconnect() }
 }
}

data class Endpoint(val host: String, val port: Int, val millis: Double)
object Scanner {
 // This is the supplied gist's UDP probe, NOT an authenticated WireGuard handshake.
 private val probe = "041d69e67922099aa0b93d1e7b309ec5851ae2a3d6bf82a8bb5bb03ed46fb2346500000000000000000000000077a4a8cd5d883e66088e5f70adb42f8a"
  .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
 suspend fun scan(progress: suspend (Int, Int) -> Unit): List<Endpoint> = coroutineScope {
  val candidates = listOf(192,193).flatMap { subnet -> (0..255).flatMap { host -> listOf(2408,500,1701,4500).map { "162.159.$subnet.$host" to it } } }.shuffled()
  val semaphore = Semaphore(24)
  val completed = java.util.concurrent.atomic.AtomicInteger()
  candidates.map { (host, port) -> async(Dispatchers.IO) {
   semaphore.withPermit {
    ensureActive()
    val result = runCatching { DatagramSocket().use { socket ->
     socket.soTimeout = 700; socket.connect(InetAddress.getByName(host), port)
     val start = System.nanoTime()
     socket.send(DatagramPacket(probe, probe.size))
     val response = DatagramPacket(ByteArray(64), 64); socket.receive(response)
     if (response.length >= 5 && response.data.take(5) == listOf(0xcf.toByte(),0.toByte(),0.toByte(),0.toByte(),0.toByte()))
      Endpoint(host, port, (System.nanoTime()-start)/1e6) else null
    } }.getOrNull()
    val n = completed.incrementAndGet(); if (n % 32 == 0 || n == candidates.size) progress(n, candidates.size)
    result
   }
  } }.awaitAll().filterNotNull().sortedBy { it.millis }
 }
 fun vpnActive(context: Context): Boolean {
  val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
 }
 fun replace(profile: String, endpoint: Endpoint): String = profile.replace(Regex("(?m)^Endpoint\\s*=.*$"), "Endpoint = ${endpoint.host}:${endpoint.port}")
}
