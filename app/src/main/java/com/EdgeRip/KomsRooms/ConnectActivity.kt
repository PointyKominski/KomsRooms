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
import com.EdgeRip.KomsRooms.databinding.ActivityConnectBinding

/**
 * First-run setup and connection screen.
 * Detects Android TV automatically and routes to TvActivity or MainActivity.
 *
 * Hidden dev menu: tap the app title "KomsRooms" 7 times to reveal the
 * developer options panel (stable binary toggle + version info).
 */
class ConnectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectBinding
    private lateinit var vm: MainViewModel
    private lateinit var devPrefs: SharedPreferences

    // 7-tap easter egg state
    private var tapCount = 0
    private var lastTapTime = 0L
    private val TAP_WINDOW_MS = 3000L
    private val TAPS_REQUIRED = 7

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectBinding.inflate(layoutInflater)
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
            val webPort  = binding.etWebPort.text.toString().toIntOrNull() ?: 8080
            val snapPort = binding.etSnapPort.text.toString().toIntOrNull() ?: 1704

            if (ip.isEmpty()) {
                binding.etIp.error = "Enter the Pi's IP address"
                return@setOnClickListener
            }

            vm.connect(ip, webPort, snapPort)
            launchPlayer()
        }

        setupDevMenu()
    }

    // ── Hidden dev menu (7-tap on title) ─────────────────────────────────────

    private fun setupDevMenu() {
        // Version label shows current vendored version; tapping it 7 times opens dev panel
        val useStable = devPrefs.getBoolean(SnapclientService.PREF_USE_STABLE, false)
        val stableVersion = readAssetLine("snapclient_version_stable.txt") ?: "not bundled"
        val currentVersion = readAssetLine("snapclient_version_current.txt") ?: "unknown"
        binding.tvVersion.text = "v$currentVersion${if (useStable) " · using stable" else ""}"

        binding.tvVersion.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTapTime > TAP_WINDOW_MS) tapCount = 0
            lastTapTime = now
            tapCount++

            val remaining = TAPS_REQUIRED - tapCount
            if (remaining in 1..3) {
                Toast.makeText(this, "$remaining more…", Toast.LENGTH_SHORT).show()
            }
            if (tapCount >= TAPS_REQUIRED) {
                tapCount = 0
                toggleDevPanel(currentVersion, stableVersion)
            }
        }

        // Dev panel starts hidden; restore visibility if it was open before (e.g. rotation)
        refreshDevPanel(currentVersion, stableVersion)
    }

    private fun toggleDevPanel(currentVersion: String, stableVersion: String) {
        val isVisible = binding.devPanel.visibility == View.VISIBLE
        binding.devPanel.visibility = if (isVisible) View.GONE else View.VISIBLE
        if (!isVisible) {
            Toast.makeText(this, "Developer options", Toast.LENGTH_SHORT).show()
            refreshDevPanel(currentVersion, stableVersion)
        }
    }

    private fun refreshDevPanel(currentVersion: String, stableVersion: String) {
        val useStable = devPrefs.getBoolean(SnapclientService.PREF_USE_STABLE, false)

        binding.devBinaryStatus.text = buildString {
            append("Active binary: ")
            append(if (useStable) "STABLE ($stableVersion)" else "CURRENT ($currentVersion)")
            append("\n\nCurrent: $currentVersion")
            append("\nStable:  $stableVersion")
        }

        binding.btnUseStable.text   = if (useStable) "✓ Using stable" else "Switch to stable"
        binding.btnUseCurrent.text  = if (!useStable) "✓ Using current" else "Switch to current"

        binding.btnUseStable.setOnClickListener {
            devPrefs.edit().putBoolean(SnapclientService.PREF_USE_STABLE, true).apply()
            binding.tvVersion.text = "v$currentVersion · using stable"
            refreshDevPanel(currentVersion, stableVersion)
            Toast.makeText(this,
                "Switched to stable ($stableVersion) — reconnect to apply",
                Toast.LENGTH_LONG).show()
        }

        binding.btnUseCurrent.setOnClickListener {
            devPrefs.edit().putBoolean(SnapclientService.PREF_USE_STABLE, false).apply()
            binding.tvVersion.text = "v$currentVersion"
            refreshDevPanel(currentVersion, stableVersion)
            Toast.makeText(this,
                "Switched to current ($currentVersion) — reconnect to apply",
                Toast.LENGTH_LONG).show()
        }

        binding.btnPromoteToStable.setOnClickListener {
            // This just tells the user what to do — actual promotion is a build-time operation
            Toast.makeText(this,
                "To promote current → stable, run:\n  scripts/promote_stable.sh\nthen commit & push.",
                Toast.LENGTH_LONG).show()
        }
    }

    /** Read the first line of a small text file bundled in assets (version tags). */
    private fun readAssetLine(name: String): String? = try {
        assets.open(name).bufferedReader().readLine()?.trim()
    } catch (e: Exception) { null }

    // ─────────────────────────────────────────────────────────────────────────

    private fun launchPlayer() {
        val uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
        val isTV = uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

        val target = if (isTV) TvActivity::class.java else MainActivity::class.java
        startActivity(Intent(this, target))
    }
}
