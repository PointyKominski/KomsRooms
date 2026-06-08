package com.EdgeRip.KomsRooms

import android.graphics.drawable.GradientDrawable
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vm = ViewModelProvider(this)[MainViewModel::class.java]

        if (PiApiClient.piIp.isEmpty()) {
            vm.reconnectSaved()
        }

        observePlayerState()
        wireControls()

        // Give initial D-pad focus to the play/pause button
        binding.btnPlayPause.requestFocus()
    }

    private fun observePlayerState() {
        lifecycleScope.launch {
            vm.player.collectLatest { state ->
                binding.tvTitle.text  = state.title.ifEmpty  { "Spotify" }
                binding.tvArtist.text = state.artist.ifEmpty { "Connect" }
                binding.tvAlbum.text  = state.album

                binding.btnPlayPause.text = if (state.playing) "⏸" else "▶"

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
        binding.btnPrev.setOnClickListener      { vm.playerCommand("prev") }
        binding.btnPlayPause.setOnClickListener { vm.playerCommand("toggle") }
        binding.btnNext.setOnClickListener      { vm.playerCommand("next") }
    }

    private fun fmtMs(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
