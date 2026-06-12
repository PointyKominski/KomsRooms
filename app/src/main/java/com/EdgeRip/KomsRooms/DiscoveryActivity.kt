package com.EdgeRip.KomsRooms

import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.app.UiModeManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.EdgeRip.KomsRooms.databinding.ActivityDiscoveryBinding
import kotlinx.coroutines.launch

/**
 * Main entry screen.
 *
 * Discovers KomsRooms servers by scanning the local subnet for hosts
 * responding to the Snapcast JSON-RPC on port 1705.  No mDNS / name
 * resolution used — pure direct IP TCP connections.
 *
 * The server name shown in the list comes from Server.GetStatus, which
 * returns whatever name was set in snapserver.conf on the Pi.
 */
class DiscoveryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiscoveryBinding
    private lateinit var vm: MainViewModel
    private lateinit var devPrefs: SharedPreferences

    private val discoveredServers = mutableListOf<SnapcastServer>()
    private lateinit var adapter: ServerListAdapter

    private var tapCount = 0
    private var lastTapTime = 0L

    data class SnapcastServer(
        val name: String,
        val host: String,
        val snapPort: Int = 1704,
        val webPort: Int = 5900
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiscoveryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vm = ViewModelProvider(this)[MainViewModel::class.java]
        devPrefs = getSharedPreferences(SnapclientService.PREFS_NAME, MODE_PRIVATE)

        // Last connected server shortcut
        if (vm.isConfigured) {
            binding.lastServerCard.visibility = View.VISIBLE
            binding.tvLastServer.text = vm.savedIp
            binding.btnReconnect.setOnClickListener { launchPlayer() }
        }

        adapter = ServerListAdapter(discoveredServers) { server ->
            vm.connect(server.host, server.webPort, server.snapPort)
            launchPlayer()
        }
        binding.rvServers.layoutManager = LinearLayoutManager(this)
        binding.rvServers.adapter = adapter

        binding.btnManual.setOnClickListener {
            startActivity(Intent(this, ManualConnectActivity::class.java))
        }

        binding.btnRefresh.setOnClickListener { startScan() }

        setupDevMenu()
        startScan()
    }

    // ── Subnet scan ──────────────────────────────────────────────────────────

    private fun startScan() {
        discoveredServers.clear()
        adapter.notifyDataSetChanged()
        binding.tvScanStatus.text = "Scanning…"
        binding.progressScan.visibility = View.VISIBLE

        val subnet = localSubnet()
        if (subnet == null) {
            binding.tvScanStatus.text = "No network — use Manual"
            binding.progressScan.visibility = View.GONE
            return
        }

        // Show this device's full IP + subnet so the user can verify it matches the Pi's network
        val myIp = localIp() ?: "$subnet.?"
        binding.tvScanStatus.text = "Scanning $subnet.0/24  (this device: $myIp)…"

        lifecycleScope.launch {
            val results = PiApiClient.scanForServers(subnet, rpcPort = 1705, timeoutMs = 600)

            discoveredServers.clear()
            results.forEach { (ip, name) ->
                discoveredServers.add(SnapcastServer(name = name, host = ip))
            }
            adapter.notifyDataSetChanged()

            binding.progressScan.visibility = View.GONE
            binding.tvScanStatus.text = when {
                discoveredServers.isEmpty() -> "No servers found on $subnet.0/24 — try Manual"
                discoveredServers.size == 1 -> "1 server found on $subnet.0/24"
                else -> "${discoveredServers.size} servers found on $subnet.0/24"
            }
        }
    }

    /**
     * Get the device's current Wi-Fi subnet prefix, e.g. "192.168.1".
     * Returns null if not connected to Wi-Fi.
     */
    /**
     * Returns the /24 subnet prefix of this device's current IP, e.g. "192.168.1".
     *
     * Strategy (most-reliable first):
     *  1. Enumerate all network interfaces and pick the first IPv4 that isn't
     *     loopback or link-local — works on Android TV / Chromecast and all
     *     Android versions without deprecated APIs.
     *  2. Fall back to WifiManager.connectionInfo (deprecated in API 31 but
     *     still works on most devices).
     */
    private fun localSubnet(): String? {
        // Method 1: network interfaces (works everywhere, including Android TV)
        try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
            if (ifaces != null) {
                for (iface in ifaces.asSequence()) {
                    if (!iface.isUp || iface.isLoopback) continue
                    for (addr in iface.inetAddresses.asSequence()) {
                        if (addr is java.net.Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                            val parts = addr.hostAddress?.split(".") ?: continue
                            if (parts.size == 4) return "${parts[0]}.${parts[1]}.${parts[2]}"
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Method 2: WifiManager fallback (deprecated API 31+ but still functional)
        return try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val ip = wm.connectionInfo?.ipAddress ?: return null
            if (ip == 0) return null
            val a = ip and 0xFF
            val b = (ip shr 8) and 0xFF
            val c = (ip shr 16) and 0xFF
            "$a.$b.$c"
        } catch (_: Exception) { null }
    }

    /** Returns this device's full IPv4 address, e.g. "192.168.1.42". */
    private fun localIp(): String? {
        try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
            if (ifaces != null) {
                for (iface in ifaces.asSequence()) {
                    if (!iface.isUp || iface.isLoopback) continue
                    for (addr in iface.inetAddresses.asSequence()) {
                        if (addr is java.net.Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                            return addr.hostAddress
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    override fun onResume() {
        super.onResume()
        if (discoveredServers.isEmpty()) startScan()
    }

    // ── Dev menu ─────────────────────────────────────────────────────────────

    private fun setupDevMenu() {
        val useStable = devPrefs.getBoolean(SnapclientService.PREF_USE_STABLE, false)
        val snapVersion = readAssetLine("snapclient_version_current.txt") ?: "unknown"
        val appVersion = BuildConfig.VERSION_NAME
        binding.tvVersion.text = "KomsRoom $appVersion · snapclient $snapVersion${if (useStable) " (stable)" else ""}"

        binding.tvVersion.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTapTime > 3000L) tapCount = 0
            lastTapTime = now
            tapCount++
            if (tapCount in 4..6) Toast.makeText(this, "${7 - tapCount} more…", Toast.LENGTH_SHORT).show()
            if (tapCount >= 7) {
                tapCount = 0
                startActivity(Intent(this, ManualConnectActivity::class.java))
            }
        }
    }

    private fun readAssetLine(name: String): String? = try {
        assets.open(name).bufferedReader().readLine()?.trim()
    } catch (e: Exception) { null }

    private fun launchPlayer() {
        val uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
        val isTV = uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        startActivity(Intent(this, if (isTV) TvActivity::class.java else MainActivity::class.java))
    }
}
