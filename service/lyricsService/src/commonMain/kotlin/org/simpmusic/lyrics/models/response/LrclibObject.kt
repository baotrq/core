package org.simpmusic.lyrics.models.response

import kotlinx.serialization.Serializable

@Serializable
data class LrclibObject(
    val id: Int,
    val name: String,
    val trackName: String?,
    val artistName: String?,
    val albumName: String?,
    // LRCLIB answers null for some entries, and one of those used to fail the whole search.
    val duration: Float? = null,
    val instrumental: Boolean,
    val plainLyrics: String?,
    val syncedLyrics: String?,
)