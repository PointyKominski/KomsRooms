package com.EdgeRip.KomsRooms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File

/**
 * Foreground service that manages the snapclient binary subprocess.
 *
 * The snapclient binary is bundled in app/src/main/assets/ as:
 *   snapclient_arm64-v8a        (current — updated by sync workflow)
 *   snapclient_stable_arm64-v8a (pinned stable fallback)
 *   snapclient_armeabi-v7a / snapclient_stable_armeabi-v7a  (same, 32-bit)
 *
 * The active binary is controlled by the "use_stable_binary" SharedPreference,
 * toggled via the hidden dev menu in ConnectActivity (7-tap on the version label).
 */
class SnapclientService : Service() {

    companion object {
        const val ACTION_START        = "com.EdgeRip.KomsRooms.START"
        const val ACTION_STOP         = "com.EdgeRip.KomsRooms.STOP"
        const val EXTRA_SERVER_IP     = "SERVER_IP"
        const val EXTRA_SERVER_PORT   = "SERVER_PORT"
        private const val NOTIF_ID    = 1001
        private const val CHANNEL_ID  = "komsrooms_audio"
        private const val TAG         = "SnapclientService"
        const val PREFS_NAME          = "komsrooms_dev"
        const val PREF_USE_STABLE     = "use_stable_binary"
    }

    private var process: Process? = null
    private var currentIp: String = ""

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val ip   = intent.getStringExtra(EXTRA_SERVER_IP)   ?: return START_NOT_STICKY
                val port = intent.getIntExtra(EXTRA_SERVER_PORT, 1704)
                currentIp = ip
                val useStable = prefs.getBoolean(PREF_USE_STABLE, false)
                val label = if (useStable) "stable" else "current"
                startForeground(NOTIF_ID, buildNotification("Connecting to $ip… [$label]"))
                startSnapclient(ip, port)
            }
            ACTION_STOP -> {
                stopSnapclient()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startSnapclient(ip: String, port: Int) {
        stopSnapclient()

        val binary = nativeBinary()
        if (binary == null) {
            Log.e(TAG, "snapclient binary not found in nativeLibraryDir — did the build workflow run?")
            updateNotification("Error: binary missing")
            return
        }

        val useStable = prefs.getBoolean(PREF_USE_STABLE, false)
        val label = if (useStable) "stable" else "current"
        Log.i(TAG, "Using $label snapclient binary: ${binary.absolutePath}")

        // Try Oboe (AAudio) first; older builds fall back to OpenSL ES
        for (player in listOf("oboe", "opensl")) {
            try {
                val cmd = listOf(binary.absolutePath,
                    "-h", ip, "-p", port.toString(),
                    "--player", player,
                    "--logfilter", "*:info")
                Log.i(TAG, "Launching: ${cmd.joinToString(" ")}")
                process = ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start()

                // Stream logs to Logcat in background
                Thread {
                    process?.inputStream?.bufferedReader()?.forEachLine { line ->
                        Log.d(TAG, "snapclient[$player]: $line")
                        if (line.contains("error", ignoreCase = true) ||
                            line.contains("failed", ignoreCase = true)) {
                            Log.w(TAG, "snapclient error line: $line")
                        }
                    }
                }.apply { isDaemon = true }.start()

                updateNotification("Playing · $ip [$label]")
                return  // success — don't try next player
            } catch (e: Exception) {
                Log.w(TAG, "Failed with player=$player: ${e.message}")
                process?.destroy(); process = null
            }
        }

        Log.e(TAG, "All player backends failed")
        updateNotification("Audio error — check Logcat")
    }

    private fun stopSnapclient() {
        process?.destroy()
        process = null
        Log.i(TAG, "snapclient stopped")
    }

    /**
     * Locate the snapclient binary in the app's nativeLibraryDir.
     *
     * Binaries are shipped as .so files in jniLibs so Android installs them
     * to nativeLibraryDir, which is always mounted executable (unlike
     * /data/user/ which is noexec on modern Android).
     *
     * File names:
     *   libsnapclient.so         ← current
     *   libsnapclient_stable.so  ← pinned stable fallback
     */
    private fun nativeBinary(): File? {
        val libDir = applicationInfo.nativeLibraryDir
        val useStable = prefs.getBoolean(PREF_USE_STABLE, false)

        val preferred = File(libDir, if (useStable) "libsnapclient_stable.so" else "libsnapclient.so")
        val fallback  = File(libDir, "libsnapclient.so")

        return when {
            preferred.exists() && preferred.canExecute() -> {
                Log.i(TAG, "Found binary: ${preferred.absolutePath}")
                preferred
            }
            useStable && fallback.exists() && fallback.canExecute() -> {
                Log.w(TAG, "Stable binary not found, falling back to current")
                fallback
            }
            else -> {
                Log.e(TAG, "No executable snapclient binary in $libDir")
                Log.e(TAG, "Contents: ${File(libDir).listFiles()?.joinToString { it.name } ?: "empty"}")
                null
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "KomsRooms Audio",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Snapcast audio playback"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, ConnectActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KomsRooms")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        stopSnapclient()
        super.onDestroy()
    }
}
