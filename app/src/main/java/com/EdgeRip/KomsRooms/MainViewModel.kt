package com.EdgeRip.KomsRooms

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.EdgeRip.KomsRooms.model.PlayerState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("komsrooms_prefs", 0)

    private val _player = MutableStateFlow(PlayerState())
    val player: StateFlow<PlayerState> = _player.asStateFlow()

    private val _eq = MutableStateFlow(
        linkedMapOf(
            "b60"   to 0f,
            "b310"  to 0f,
            "b880"  to 0f,
            "b3500" to 0f,
            "b10k"  to 0f
        )
    )
    val eq: StateFlow<Map<String, Float>> = _eq.asStateFlow()

    private var pollJob: Job? = null

    // ── Saved settings ────────────────────────────────────────────────────────

    var savedIp: String
        get() = prefs.getString("pi_ip", "") ?: ""
        set(v) { prefs.edit().putString("pi_ip", v).apply() }

    var savedWebPort: Int
        get() = prefs.getInt("web_port", 8080)
        set(v) { prefs.edit().putInt("web_port", v).apply() }

    var savedSnapPort: Int
        get() = prefs.getInt("snap_port", 1704)
        set(v) { prefs.edit().putInt("snap_port", v).apply() }

    val isConfigured: Boolean get() = savedIp.isNotEmpty()

    // ── Connect / disconnect ──────────────────────────────────────────────────

    fun connect(ip: String, webPort: Int = 8080, snapPort: Int = 1704) {
        savedIp       = ip
        savedWebPort  = webPort
        savedSnapPort = snapPort

        PiApiClient.piIp     = ip
        PiApiClient.webPort  = webPort
        PiApiClient.snapPort = snapPort

        startSnapclientService(ip, snapPort)
        startPolling()
    }

    fun reconnectSaved() {
        if (!isConfigured) return
        PiApiClient.piIp     = savedIp
        PiApiClient.webPort  = savedWebPort
        PiApiClient.snapPort = savedSnapPort
        startSnapclientService(savedIp, savedSnapPort)
        startPolling()
    }

    fun disconnect() {
        pollJob?.cancel()
        val ctx = getApplication<Application>()
        ctx.startService(Intent(ctx, SnapclientService::class.java).apply {
            action = SnapclientService.ACTION_STOP
        })
    }

    // ── Player controls ───────────────────────────────────────────────────────

    fun playerCommand(cmd: String) {
        viewModelScope.launch {
            PiApiClient.playerCommand(cmd)
            delay(300)
            refreshNow()
        }
    }

    fun ensurePolling() {
        if (pollJob == null || pollJob?.isActive == false) {
            startPolling()
        }
    }

    fun setVolume(clientId: String, percent: Int) {
        viewModelScope.launch { PiApiClient.setVolume(clientId, percent) }
    }

    fun muteSnapClient(deviceIp: String, muted: Boolean) {
        viewModelScope.launch { PiApiClient.muteClient(deviceIp, muted) }
    }

    // ── EQ ───────────────────────────────────────────────────────────────────

    fun setEqBand(key: String, value: Float, clientId: String = "") {
        val updated = LinkedHashMap(_eq.value).also { it[key] = value }
        _eq.value = updated
        viewModelScope.launch { PiApiClient.setEq(clientId, updated) }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun startSnapclientService(ip: String, snapPort: Int) {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, SnapclientService::class.java).apply {
            action = SnapclientService.ACTION_START
            putExtra(SnapclientService.EXTRA_SERVER_IP, ip)
            putExtra(SnapclientService.EXTRA_SERVER_PORT, snapPort)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                refreshNow()
                delay(3_000)
            }
        }
    }

    private suspend fun refreshNow() {
        _player.value = PiApiClient.getStatus()
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}
