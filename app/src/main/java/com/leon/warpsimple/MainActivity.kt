package com.leon.warpsimple

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.*

class MainActivity : Activity() {
 private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
 private lateinit var status: TextView
 private lateinit var generate: Button
 private lateinit var scan: Button
 private lateinit var connect: Button
 private lateinit var store: ProfileStore
 private lateinit var backend: GoBackend
 private var profile: String? = null
 private var busy = false
 private var connected = false
 private val tunnel = object : Tunnel {
  override fun getName() = "WarpSimple"
  override fun onStateChange(state: Tunnel.State) { runOnUiThread {
   if (state == Tunnel.State.DOWN) { connected = false; refresh() }
  } }
 }
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  store = ProfileStore(applicationContext); backend = GoBackend(applicationContext)
  val layout = LinearLayout(this).apply {
   orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
   setPadding(40,32,40,32); setBackgroundColor(Color.rgb(14,24,40))
  }
  fun text(value: String, size: Float) = TextView(this).apply {
   text = value; textSize = size; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
   setPadding(0,16,0,16); layout.addView(this)
  }
  text("WARP", 34f)
  status = text("Not configured", 18f)
  fun button(label: String) = Button(this).apply {
   text = label; isAllCaps = false; minHeight = 56
   layout.addView(this, LinearLayout.LayoutParams(360, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 12 })
  }
  generate = button("Generate")
  scan = button("Scan")
  connect = button("Connect")
  text("Phone • Android TV", 14f)
  setContentView(layout)
  try { profile = store.load() } catch (_: Exception) { status.text = "Saved profile could not be opened. Generate again." }
  generate.setOnClickListener {
   AlertDialog.Builder(this).setTitle("Generate WARP profile")
    .setMessage("Registration accepts Cloudflare's terms: https://www.cloudflare.com/application/terms/\nIf registration is blocked, connect another VPN and retry.")
    .setNegativeButton("Cancel", null).setPositiveButton("Accept & Generate") { _, _ -> operation {
     status.text = "Generating…"
     val result = Registration.generate()
     withContext(Dispatchers.IO) { store.save(result) }; profile = result
     status.text = "Profile ready. Disconnect temporary VPN, then Scan."
    } }.show()
  }
  scan.setOnClickListener { operation {
   check(!Scanner.vpnActive(this@MainActivity)) { "Disconnect the other VPN before scanning your own network." }
   val results = Scanner.scan { n, total -> withContext(Dispatchers.Main) { status.text = "Finding endpoint… $n / $total" } }
   check(results.isNotEmpty()) { "No UDP responses. Try another network or Connect with the generated endpoint." }
   val updated = Scanner.replace(requireNotNull(profile), results.first())
   withContext(Dispatchers.IO) { store.save(updated) }; profile = updated
   status.text = "Endpoint selected. Ready to connect."
  } }
  connect.setOnClickListener {
   if (connected) operation { withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.DOWN, null) }; connected = false; status.text = "Disconnected" }
   else {
    val permission = GoBackend.VpnService.prepare(this)
    if (permission != null) startActivityForResult(permission, 10) else connectTunnel()
   }
  }
  scope.launch {
   connected = withContext(Dispatchers.IO) { backend.getState(tunnel) == Tunnel.State.UP }
   if (connected) status.text = "Tunnel active" else if (profile != null) status.text = "Profile ready"
   refresh()
  }
  refresh(); (if (profile == null) generate else scan).requestFocus()
 }
 private fun refresh() {
  generate.isEnabled = !busy && !connected && profile == null
  scan.isEnabled = !busy && !connected && profile != null
  connect.isEnabled = !busy && profile != null
  connect.text = if (connected) "Disconnect" else "Connect"
 }
 private fun operation(block: suspend () -> Unit) {
  if (busy) return
  busy = true; refresh()
  scope.launch {
   try { block() } catch (e: CancellationException) { throw e }
   catch (e: Exception) { status.text = e.message ?: "Operation failed. Retry." }
   finally { busy = false; refresh() }
  }
 }
 private fun connectTunnel() = operation {
  status.text = "Connecting…"
  try {
   val config = withContext(Dispatchers.IO) { Config.parse(requireNotNull(profile).byteInputStream()) }
   withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.UP, config) }
   withTimeout(20000) {
    while (true) {
     val verified = withContext(Dispatchers.IO) {
      val stats = backend.getStatistics(tunnel)
      stats.peers().any { (stats.peer(it)?.latestHandshakeEpochMillis() ?: 0) > 0 }
     }
     if (verified) break
     delay(1000)
    }
   }
   connected = true; status.text = "Connected • WireGuard handshake verified"
  } catch (e: Exception) {
   withContext(NonCancellable + Dispatchers.IO) { runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) } }
   connected = false
   if (e is TimeoutCancellationException) error("Handshake timed out. Scan again or try another network.")
   throw e
  }
 }
 @Deprecated("Activity result compatibility")
 override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
  super.onActivityResult(requestCode, resultCode, data)
  if (requestCode == 10 && resultCode == RESULT_OK) connectTunnel()
 }
 override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
