# WARP Simple — Android phone and TV

Source implementation, not a tested/released APK. Kotlin UI, native Android registration port of wgcf's API contract, and official WireGuard GoBackend. The wgcf Go executable itself is not bundled. Registration uses Android HTTPS; it does not reproduce wgcf's custom TLS fingerprint. Cloudflare can reject it; API compatibility requires device testing.

## Build APK
Extract this ZIP. Upload the contents of `WarpSimple/` into the root of a GitHub repository, including `.github/workflows/build-apk.yml`. Push, then open Actions → Build Android APK → download `WarpSimple-debug-apk`. The workflow uses Gradle 8.9 directly; no missing `gradlew` dependency. Debug APKs are for testing; use a stable private release signing key for distribution/updates.

Alternatively open `WarpSimple/` in Android Studio, install SDK 35, select Gradle 8.9 and JDK 17, then Build APK. Command line with installed SDK/Gradle: `gradle :app:assembleDebug`.

## Flow
1. Generate: accept Cloudflare terms. If API is blocked, manually connect another VPN and retry.
2. Profile is encrypted with Android Keystore in private app storage, with backup disabled. No keys or endpoint addresses are displayed or logged by this app.
3. Disconnect the temporary VPN. Scan checks every address in two IPv4 /24 ranges against four ports (2048 candidates). The supplied gist uses a fixed UDP probe; this is not authentication, packet-loss measurement, or proof that WARP will work. The app selects the fastest observed matching UDP reply. It does not claim to enumerate all Cloudflare endpoints.
4. Connect: accept Android VPN permission. A real WireGuard handshake must appear in backend statistics within 20 seconds before “Connected” is shown. Failure shuts the tunnel down. Disconnect uses the same button.

## Validation and limits
No Android SDK/emulator was available in the creation environment. Manifest XML, workflow/config structure, probe byte equivalence and dependency source APIs were inspected; APK compilation, registration, VPN routing, process lifecycle and remote navigation still require testing. Do not call this production ready.

No automatic authenticated scan of all endpoints, automatic failover, config export/import, settings, analytics, Python runtime, or WARP+ support. Only one sampled UDP reply per candidate; scan times cannot be presented as WireGuard RTT or packet loss. Scans use the current network; VPN detection blocks scanning through a temporary VPN. UI hides IP/port and probe timing. IPv6 tunnel routing is supported in the generated config, but IPv6 endpoint scanning is not.

Background/reboot/always-on behavior and long-running service reliability are not validated. The pinned WireGuard backend manages its own VpnService. MainActivity operation cancellation shuts down a connection in progress; a connected backend can outlive the activity. Test Android 6 TV and modern phone firmware before distribution.

## Sources and licensing
- wgcf API port: https://github.com/ViRb3/wgcf/blob/master/cloudflare/api.go (MIT notice in WGCF-LICENSE.txt)
- Provided UDP probe: https://gist.github.com/thanksshu/d65ef7ed5fed7049f599e7a863f197db . No explicit license was supplied by the gist; resolve redistribution permission before public release.
- WireGuard Android tunnel: https://github.com/WireGuard/wireguard-android (Apache-2.0 dependency)
- Cloudflare terms: https://www.cloudflare.com/application/terms/

This project is not affiliated with Cloudflare. No account was registered during source creation.
