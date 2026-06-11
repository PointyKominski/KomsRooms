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
 * Main entry screen — discovers KomsRooms servers on the local network
 * via mDNS (_snapcast._tcp) and lists them for one-tap connection.
 *
 * "Manual" button opens ManualConnectActivity.
 * Version label: 7-tap for developer options.
 */
class DiscoveryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiscoveryBinding
    private lateinit var vm: MainViewModel
    private lateinit var devPrefs: SharedPreferences

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val discoveredServers = mutableListOf<SnapcastServer>()
    private lateinit var adapter: ServerListAdapter

    private var tapCount = 0
    private var lastTapTime = 0L

    data class SnapcastServer(
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

        // Server list
        adapter = ServerListAdapter(discoveredServers) { server ->
            vm.connect(server.host, server.webPort, server.snapPort)
            launchPlayer()
        }
        binding.rvServers.layoutManager = LinearLayoutManager(this)
        binding.rvServers.adapter = adapter

        binding.btnManual.setOnClickListener {
            startActivity(Intent(this, ManualConnectActivity::class.java))
        }

        binding.btnRefresh.setOnClickListener { startDiscovery() }

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
                        binding.tvScanStatus.text = "Scan failed — try Manual"
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
                            // Only use IPv4 addresses — skip link-local / IPv6
                            val addr = si.host ?: return
                            val host = addr.hostAddress ?: return
                            if (host.contains(':') || host.startsWith("169.254")) return

                            val snapPort = si.port.takeIf { it > 0 } ?: 1704
                            val webPort  = si.attributes["webport"]
                                ?.let { String(it).toIntOrNull() } ?: 5900

                            val server = SnapcastServer(host, snapPort, webPort)

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
                    // We don't store the name anymore so just re-scan on loss
                }
            }
            discoveryListener = listener
            mgr.discoverServices("_snapcast._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        }

        binding.root.postDelayed({
            if (discoveredServers.isEmpty()) {
                binding.tvScanStatus.text = "No servers found — try Manual"
                binding.progressScan.visibility = View.GONE
            }
        }, 8000)
    }

    private fun stopDiscovery() {
        try { discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) } }
        catch (e: Exception) {}
        discoveryListener = null
    }

    override fun onResume() {
        super.onResume()
        if (discoveredServers.isEmpty()) startDiscovery()
    }

    override fun onDestroy() {
        stopDiscovery()
        super.onDestroy()
    }

    // ── Dev menu ─────────────────────────────────────────────────────────────

    private fun setupDevMenu() {
        val useStable = devPrefs.getBoolean(SnapclientService.PREF_USE_STABLE, false)
        val currentVersion = readAssetLine("snapclient_version_current.txt") ?: "unknown"
        binding.tvVersion.text = "v$currentVersion${if (useStable) " · stable" else ""}"

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
