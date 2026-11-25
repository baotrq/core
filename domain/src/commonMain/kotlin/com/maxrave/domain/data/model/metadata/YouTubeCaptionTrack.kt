package com.maxrave.domain.data.model.metadata

/**
 * One transcript YouTube lists for a video. [id] is YouTube's `vssId`, unique per track, and is what
 * a per-song pick stores. [isAuto] marks YouTube's speech recognition, which is in the sung language.
 */
data class YouTubeCaptionTrack(
    val id: String,
    val languageCode: String,
    val name: String,
    val isAuto: Boolean,
)
