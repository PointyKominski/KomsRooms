package com.EdgeRip.KomsRooms.model

data class PlayerState(
    val title: String       = "",
    val artist: String      = "",
    val album: String       = "",
    val artUrl: String?     = null,
    val positionMs: Long    = 0L,
    val durationMs: Long    = 0L,
    val playing: Boolean    = false,
    val hasTrack: Boolean   = false,
    val connected: Boolean  = false,
    val error: String?      = null
)
