package com.EdgeRip.KomsRooms

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import com.EdgeRip.KomsRooms.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Mobile now-playing screen (portrait, touch).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var vm: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vm = ViewModelProvider(this)[MainViewModel::class.java]

        // If launched directly (e.g. from notification) and we have saved config, reconnect
        if (!PiApiClient.piIp.isNotEmpty()) {
            vm.reconnectSaved()
        }

        observePlayerState()
        wireControls()
    }

    private fun observePlayerState() {
        lifecycleScope.launch {
            vm.player.collectLatest { state ->
                // Track info
                binding.tvTitle.text  = state.title.ifEmpty  { "Spotify" }
                binding.tvArtist.text = state.artist.ifEmpty { "Connect" }
                binding.tvAlbum.text  = state.album

                // Play/pause icon
                binding.btnPlayPause.text = if (state.playing) "⏸" else "▶"

                // Progress
                if (state.durationMs > 0) {
                    binding.seekBar.max      = state.durationMs.toInt()
                    binding.seekBar.progress = state.positionMs.toInt()
                    binding.tvPosition.text  = fmtMs(state.positionMs)
                    binding.tvDuration.text  = fmtMs(state.durationMs)
                }

                // Album art + dynamic background colour
                if (!state.artUrl.isNullOrEmpty()) {
                    Glide.with(this@MainActivity)
                        .asBitmap()
                        .load(state.artUrl)
                        .into(object : CustomTarget<Bitmap>() {
                            override fun onResourceReady(
                                bmp: Bitmap, transition: Transition<in Bitmap>?
                            ) {
                                binding.ivAlbumArt.setImageBitmap(bmp)
                                // Tint background with dominant colour from art
                                Palette.from(bmp).generate { palette ->
                                    val swatch = palette?.darkVibrantSwatch
                                        ?: palette?.dominantSwatch
                                    if (swatch != null) {
                                        val grad = GradientDrawable(
                                            GradientDrawable.Orientation.TOP_BOTTOM,
                                            intArrayOf(swatch.rgb, 0xFF0D0D0D.toInt())
                                        )
                                        binding.root.background = grad
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
