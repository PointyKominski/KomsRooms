package com.EdgeRip.KomsRooms

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import com.EdgeRip.KomsRooms.databinding.FragmentManualConnectBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Bottom sheet for manual IP/port entry and the hidden developer panel.
 */
class ManualConnectFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentManualConnectBinding? = null
    private val binding get() = _binding!!

    private var onConnect: ((ip: String, webPort: Int, snapPort: Int) -> Unit)? = null
    private var showDev = false
    private var currentVersion = ""
    private var stableVersion = ""
    private var devPrefs: SharedPreferences? = null
    private var onDevToggle: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentManualConnectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Pre-fill saved values from ViewModel if available
        try {
            val vm = (activity as? DiscoveryActivity)?.let {
                androidx.lifecycle.ViewModelProvider(it)[MainViewModel::class.java]
            }
            vm?.let {
                if (it.isConfigured) {
                    binding.etIp.setText(it.savedIp)
                    binding.etWebPort.setText(it.savedWebPort.toString())
                    binding.etSnapPort.setText(it.savedSnapPort.toString())
                }
            }
        } catch (e: Exception) { /* ignore */ }

        binding.btnConnect.setOnClickListener {
            val ip       = binding.etIp.text.toString().trim()
            val webPort  = binding.etWebPort.text.toString().toIntOrNull() ?: 5900
            val snapPort = binding.etSnapPort.text.toString().toIntOrNull() ?: 1704
            if (ip.isEmpty()) {
                binding.etIp.error = "Enter the server's IP address"
                return@setOnClickListener
            }
            dismiss()
            onConnect?.invoke(ip, webPort, snapPort)
        }

        // Developer panel (shown when opened via 7-tap)
        if (showDev) {
            binding.devSection.visibility = View.VISIBLE
            refreshDevPanel()
        }
    }

    private fun refreshDevPanel() {
        val prefs = devPrefs ?: return
        val useStable = prefs.getBoolean(SnapclientService.PREF_USE_STABLE, false)

        binding.devBinaryStatus.text = buildString {
            append("Active: ${if (useStable) "STABLE ($stableVersion)" else "CURRENT ($currentVersion)"}")
            append("\n\nCurrent: $currentVersion")
            append("\nStable:  $stableVersion")
        }
        binding.btnUseStable.text   = if (useStable) "✓ Using stable"  else "Switch to stable"
        binding.btnUseCurrent.text  = if (!useStable) "✓ Using current" else "Switch to current"

        binding.btnUseStable.setOnClickListener {
            prefs.edit().putBoolean(SnapclientService.PREF_USE_STABLE, true).apply()
            onDevToggle?.invoke()
            refreshDevPanel()
            Toast.makeText(context, "Using stable ($stableVersion) — reconnect to apply",
                Toast.LENGTH_LONG).show()
        }
        binding.btnUseCurrent.setOnClickListener {
            prefs.edit().putBoolean(SnapclientService.PREF_USE_STABLE, false).apply()
            onDevToggle?.invoke()
            refreshDevPanel()
            Toast.makeText(context, "Using current ($currentVersion) — reconnect to apply",
                Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun show(
            fm: FragmentManager,
            onConnect: (ip: String, webPort: Int, snapPort: Int) -> Unit
        ) {
            ManualConnectFragment().apply {
                this.onConnect = onConnect
            }.show(fm, "manual_connect")
        }

        fun showDevPanel(
            fm: FragmentManager,
            currentVersion: String,
            stableVersion: String,
            devPrefs: SharedPreferences,
            onDevToggle: () -> Unit
        ) {
            ManualConnectFragment().apply {
                this.showDev = true
                this.currentVersion = currentVersion
                this.stableVersion = stableVersion
                this.devPrefs = devPrefs
                this.onDevToggle = onDevToggle
            }.show(fm, "dev_panel")
        }
    }
}
