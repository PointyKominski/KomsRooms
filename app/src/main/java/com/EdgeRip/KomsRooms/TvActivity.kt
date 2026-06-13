package com.EdgeRip.KomsRooms

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import com.EdgeRip.KomsRooms.databinding.ActivityTvBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * TV / Chromecast now-playing screen (landscape 16:9, D-pad navigable).
 *
 * Layout: full-bleed album art on the left half,
 *         track info + controls on the right half.
 * All interactive buttons are focusable and have visible focus rings
 * so they can be navigated with a remote / D-pad.
 */
class TvActivity : FragmentActivity() {

    private lateinit var binding: ActivityTvBinding
    private lateinit var vm: MainViewModel
    private var isMuted = false
    private var currentTrackUri: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vm = ViewModelProvider(this)[MainViewModel::class.java]

        // Always ensure polling starts — ViewModel is a fresh instance per-activity
        if (PiApiClient.piIp.isEmpty()) vm.reconnectSaved() else vm.ensurePolling()

        // Show which server we're connected to
        val ip = vm.savedIp.ifEmpty { "unknown" }
        binding.tvStatus.text = "Connected to $ip"

        // "Change server" goes back to discovery
        binding.tvChangeServer.setOnClickListener {
            finish()
        }

        // Mute toggle — silences this Snapcast client only, keeps playback running
        binding.tvStopAudio.setOnClickListener {
            isMuted = !isMuted
            val deviceIp = localIp()
            if (deviceIp != null) vm.muteSnapClient(deviceIp, isMuted)
            updateMuteButton()
        }

        // Open Spotify to the currently playing track (or just Spotify if no track)
        binding.tvOpenSpotify.setOnClickListener { openSpotify() }

        // Open the snapcastbt web UI in the default browser
        binding.tvOpenWebUi.setOnClickListener {
            val url = "http://${vm.savedIp}:${vm.savedWebPort}"
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Exception) { }
        }

        updateMuteButton()
        observePlayerState()
        wireControls()

        binding.btnPlay.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        vm.ensurePolling()
    }

    private fun localIp(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces()?.asSequence()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.asSequence() }
            ?.filterIsInstance<java.net.Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    } catch (_: Exception) { null }

    private fun updateMuteButton() {
        if (isMuted) {
            binding.tvStopAudio.text = "🔇 Muted"
            binding.tvStopAudio.setTextColor(android.graphics.Color.parseColor("#f87171"))
        } else {
            binding.tvStopAudio.text = "🔊"
            binding.tvStopAudio.setTextColor(android.graphics.Color.parseColor("#99FFFFFF"))
        }
    }

    private fun observePlayerState() {
        lifecycleScope.launch {
            vm.player.collectLatest { state ->
                // Status dot: green if API responding, red if error
                val dotRes = if (state.connected)
                    android.R.drawable.presence_online
                else
                    android.R.drawable.presence_busy
                binding.statusDot.setBackgroundResource(dotRes)
                if (!state.connected && state.error != null) {
                    binding.tvStatus.text = "No response from ${vm.savedIp}"
                } else {
                    binding.tvStatus.text = "Connected to ${vm.savedIp}"
                }

                binding.tvTitle.text  = state.title.ifEmpty  { "Spotify" }
                binding.tvArtist.text = state.artist.ifEmpty { "Connect" }
                binding.tvAlbum.text  = state.album

                currentTrackUri = state.trackUri
                // play/pause/stop are separate buttons — no toggle text needed

                if (state.durationMs > 0) {
                    binding.progressBar.max      = state.durationMs.toInt()
                    binding.progressBar.progress = state.positionMs.toInt()
                    binding.tvPosition.text      = fmtMs(state.positionMs)
                    binding.tvDuration.text      = fmtMs(state.durationMs)
                }

                if (!state.artUrl.isNullOrEmpty()) {
                    Glide.with(this@TvActivity)
                        .asBitmap()
                        .load(state.artUrl)
                        .into(object : CustomTarget<Bitmap>() {
                            override fun onResourceReady(
                                bmp: Bitmap, transition: Transition<in Bitmap>?
                            ) {
                                binding.ivAlbumArt.setImageBitmap(bmp)
                                Palette.from(bmp).generate { palette ->
                                    val swatch = palette?.darkVibrantSwatch
                                        ?: palette?.dominantSwatch
                                    if (swatch != null) {
                                        binding.root.background = GradientDrawable(
                                            GradientDrawable.Orientation.LEFT_RIGHT,
                                            intArrayOf(0xFF0D0D0D.toInt(), swatch.rgb)
                                        )
                                    }
                                }
                            }
                            override fun onLoadCleared(placeholder: Drawable?) {
                                binding.ivAlbumArt.setImageResource(R.drawable.ic_music_note)
                            }
                        })
                } else {
                    binding.ivAlbumArt.setImageResource(R.drawable.ic_music_note)
                }
            }
        }
    }

    private fun wireControls() {
        binding.btnPrev.setOnClickListener  { vm.playerCommand("prev") }
        binding.btnPlay.setOnClickListener  { vm.playerCommand("play") }
        binding.btnPause.setOnClickListener { vm.playerCommand("pause") }
        binding.btnStop.setOnClickListener  { vm.playerCommand("stop") }
        binding.btnNext.setOnClickListener  { vm.playerCommand("next") }
    }

    private fun openSpotify() {
        // Try TV package first, then phone package
        for (pkg in listOf("com.spotify.tv.android", "com.spotify.music")) {
            val intent = packageManager.getLaunchIntentForPackage(pkg) ?: continue
            try { startActivity(intent); return } catch (_: Exception) { }
        }
    }

    private fun fmtMs(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
