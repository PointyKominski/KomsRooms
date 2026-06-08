package com.EdgeRip.KomsRooms

import android.content.Intent
import android.content.res.Configuration
import android.app.UiModeManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.EdgeRip.KomsRooms.databinding.ActivityConnectBinding

/**
 * First-run setup and connection screen.
 * Detects Android TV automatically and routes to TvActivity or MainActivity.
 */
class ConnectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectBinding
    private lateinit var vm: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vm = ViewModelProvider(this)[MainViewModel::class.java]

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
                binding.etIp.error = "Enter the Pi's IP address"
                return@setOnClickListener
            }

            vm.connect(ip, webPort, snapPort)
            launchPlayer()
        }
    }

    private fun launchPlayer() {
        val uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
        val isTV = uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

        val target = if (isTV) TvActivity::class.java else MainActivity::class.java
        startActivity(Intent(this, target))
        // Don't finish — back arrow returns to settings
    }
}
