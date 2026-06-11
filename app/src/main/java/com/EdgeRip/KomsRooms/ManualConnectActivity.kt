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
import com.EdgeRip.KomsRooms.databinding.ActivityManualConnectBinding

/**
 * Full-screen manual connection screen.
 * Replaces the BottomSheet — works properly on TV and phone.
 */
class ManualConnectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManualConnectBinding
    private lateinit var vm: MainViewModel
    private lateinit var devPrefs: SharedPreferences

    private var tapCount = 0
    private var lastTapTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManualConnectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vm = ViewModelProvider(this)[MainViewModel::class.java]
        devPrefs = getSharedPreferences(SnapclientService.PREFS_NAME, MODE_PRIVATE)

        // Pre-fill saved values
        if (vm.isConfigured) {
            binding.etIp.setText(vm.savedIp)
            binding.etWebPort.setText(vm.savedWebPort.toString())
            binding.etSnapPort.setText(vm.savedSnapPort.toString())
        }

        binding.btnConnect.setOnClickListener {
            val ip       = binding.etIp.text.toString().trim()
            val webPort  = binding.etWebPort.text.toString().toIntOrNull() ?: 5900
            val snapPort = binding.etSnapPort.text.toString().toIntOrNull() ?: 1704
            if (ip.isEmpty()) {
                binding.etIp.error = "Enter the server's IP address"
                return@setOnClickListener
            }
            vm.connect(ip, webPort, snapPort)
            launchPlayer()
        }

        binding.btnBack.setOnClickListener { finish() }

        setupDevMenu()
    }

    private fun setupDevMenu() {
        val useStable = devPrefs.getBoolean(SnapclientService.PREF_USE_STABLE, false)
        val stableVersion  = readAssetLine("snapclient_version_stable.txt")  ?: "not bundled"
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
                binding.devPanel.visibility =
                    if (binding.devPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                refreshDevPanel(currentVersion, stableVersion)
            }
        }
        refreshDevPanel(currentVersion, stableVersion)
    }

    private fun refreshDevPanel(currentVersion: String, stableVersion: String) {
        val useStable = devPrefs.getBoolean(SnapclientService.PREF_USE_STABLE, false)
        binding.devBinaryStatus.text =
            "Active: ${if (useStable) "STABLE ($stableVersion)" else "CURRENT ($currentVersion)"}\n" +
            "Current: $currentVersion\nStable:  $stableVersion"
        binding.btnUseStable.text   = if (useStable)  "✓ Using stable"   else "Switch to stable"
        binding.btnUseCurrent.text  = if (!useStable) "✓ Using current"  else "Switch to current"
        binding.btnUseStable.setOnClickListener {
            devPrefs.edit().putBoolean(SnapclientService.PREF_USE_STABLE, true).apply()
            binding.tvVersion.text = "v$currentVersion · stable"
            refreshDevPanel(currentVersion, stableVersion)
            Toast.makeText(this, "Using stable — reconnect to apply", Toast.LENGTH_LONG).show()
        }
        binding.btnUseCurrent.setOnClickListener {
            devPrefs.edit().putBoolean(SnapclientService.PREF_USE_STABLE, false).apply()
            binding.tvVersion.text = "v$currentVersion"
            refreshDevPanel(currentVersion, stableVersion)
            Toast.makeText(this, "Using current — reconnect to apply", Toast.LENGTH_LONG).show()
        }
    }

    private fun readAssetLine(name: String): String? = try {
        assets.open(name).bufferedReader().readLine()?.trim()
    } catch (e: Exception) { null }

    private fun launchPlayer() {
        val uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
        val isTV = uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        startActivity(Intent(this, if (isTV) TvActivity::class.java else MainActivity::class.java))
        finish()
    }
}
