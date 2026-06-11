package com.EdgeRip.KomsRooms

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.app.UiModeManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.EdgeRip.KomsRooms.databinding.ActivityDiscoveryBinding

/**
 * Main entry screen — discovers KomsRooms/Snapcast servers on the local network
 * via mDNS (_snapcast._tcp) and lists them for one-tap connection.
 *
 * "Manual" button opens ManualConnectFragment (bottom sheet) with IP/port fields.
 *
 * Hidden dev menu: tap the version label 7 times.
 */
class DiscoveryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiscoveryBinding
    private lateinit var vm: MainViewModel
    private lateinit var devPrefs: SharedPreferences

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val discoveredServers = mutableListOf<SnapcastServer>()
    private lateinit var adapter: ServerListAdapter

    // 7-tap dev menu state
    private var tapCount = 0
    private var lastTapTime = 0L
    private val TAP_WINDOW_MS = 3000L
    private val TAPS_REQUIRED = 7

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

        // If we already have a saved server, show a "reconnect" button at the top
        if (vm.isConfigured) {
            binding.lastServerCard.visibility = View.VISIBLE
            binding.tvLastServer.text = "${vm.savedIp}  ·  port ${vm.savedSnapPort}"
            binding.btnReconnect.setOnClickListener {
                launchPlayer()
            }
        }

        // RecyclerView for discovered servers
        adapter = ServerListAdapter(discoveredServers) { server ->
            vm.connect(server.host, server.webPort, server.snapPort)
            launchPlayer()
        }
        binding.rvServers.layoutManager = LinearLayoutManager(this)
        binding.rvServers.adapter = adapter

        // Manual entry button
        binding.btnManual.setOnClickListener {
            ManualConnectFragment.show(supportFragmentManager) { ip, webPort, snapPort ->
                vm.connect(ip, webPort, snapPort)
                launchPlayer()
            }
        }

        // Refresh / scan button
        binding.btnRefresh.setOnClickListener {
            startDiscovery()
        }

        setupDevMenu()
        startDiscovery()
    }

    // ── mDNS discovery ───────────────────────────────────────────────────────

    private fun startDiscovery() {
        stopDiscovery()
        discoveredServers.clear()
        adapter.notifyDataSetChanged()
        binding.tvScanStatus.text = "Scanning…"
        binding.progressScan.visibility = View.VISIBLE

        nsdManager = (getSystemService(Context.NSD_SERVICE) as NsdManager).also { mgr ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    runOnUiThread {
                        binding.tvScanStatus.text = "Scan failed (error $errorCode)"
                        binding.progressScan.visibility = View.GONE
                    }
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    mgr.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(si: NsdServiceInfo) {
                            val host = si.host?.hostAddress ?: return
                            val snapPort = si.port.takeIf { it > 0 } ?: 1704
                            // Derive web UI port: Snapcast HTTP is typically snapPort + 76 (1780),
                            // but our web UI is on 5900. Check TXT record first, fallback to 5900.
                            val webPort = si.attributes["webport"]
                                ?.let { String(it).toIntOrNull() } ?: 5900
                            val server = SnapcastServer(
                                name = si.serviceName,
                                host = host,
                                snapPort = snapPort,
                                webPort = webPort
                            )
                            runOnUiThread {
                                if (discoveredServers.none { it.host == host }) {
                                    discoveredServers.add(server)
                                    adapter.notifyItemInserted(discoveredServers.size - 1)
                                    binding.tvScanStatus.text =
                                        "${discoveredServers.size} server(s) found"
                                    binding.progressScan.visibility = View.GONE
                                }
                            }
                        }
                    })
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    runOnUiThread {
                        val removed = discoveredServers.indexOfFirst {
                            it.name == serviceInfo.serviceName
                        }
                        if (removed >= 0) {
                            discoveredServers.removeAt(removed)
                            adapter.notifyItemRemoved(removed)
                            binding.tvScanStatus.text =
                                if (discoveredServers.isEmpty()) "No servers found"
                                else "${discoveredServers.size} server(s) found"
                        }
                    }
                }
            }
            discoveryListener = listener
            mgr.discoverServices("_snapcast._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        }

        // Show "no servers found" after 8 seconds if still empty
        binding.root.postDelayed({
            if (discoveredServers.isEmpty()) {
                binding.tvScanStatus.text = "No servers found — try Manual"
                binding.progressScan.visibility = View.GONE
            }
        }, 8000)
    }

    private fun stopDiscovery() {
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (e: Exception) { /* ignore if already stopped */ }
        discoveryListener = null
    }

    override fun onResume() {
        super.onResume()
        // Re-scan when coming back to this screen
        if (discoveredServers.isEmpty()) startDiscovery()
    }

    override fun onDestroy() {
        stopDiscovery()
        super.onDestroy()
    }

    // ── Dev menu (7-tap on version label) ────────────────────────────────────

    private fun setupDevMenu() {
        val useStable = devPrefs.getBoolean(SnapclientService.PREF_USE_STABLE, false)
        val stableVersion = readAssetLine("snapclient_version_stable.txt") ?: "not bundled"
        val currentVersion = readAssetLine("snapclient_version_current.txt") ?: "unknown"
        binding.tvVersion.text = "v$currentVersion${if (useStable) " · stable" else ""}"

        binding.tvVersion.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTapTime > TAP_WINDOW_MS) tapCount = 0
            lastTapTime = now
            tapCount++
            val remaining = TAPS_REQUIRED - tapCount
            if (remaining in 1..3) Toast.makeText(this, "$remaining more…", Toast.LENGTH_SHORT).show()
            if (tapCount >= TAPS_REQUIRED) {
                tapCount = 0
                ManualConnectFragment.showDevPanel(
                    supportFragmentManager, currentVersion, stableVersion, devPrefs
                ) {
                    // Refresh version label after toggle
                    val nowStable = devPrefs.getBoolean(SnapclientService.PREF_USE_STABLE, false)
                    binding.tvVersion.text = "v$currentVersion${if (nowStable) " · stable" else ""}"
                }
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
