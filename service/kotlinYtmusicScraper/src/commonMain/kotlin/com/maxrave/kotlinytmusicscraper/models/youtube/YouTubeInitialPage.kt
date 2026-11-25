package com.maxrave.kotlinytmusicscraper.models.youtube

import com.maxrave.kotlinytmusicscraper.models.ResponseContext
import com.maxrave.kotlinytmusicscraper.models.response.PlayerResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class YouTubeInitialPage(
    @SerialName("videoDetails")
    val videoDetails: VideoDetails?,
    @SerialName("captions")
    val captions: Captions? = null,
) {
    @Serializable
    data class Captions(
        @SerialName("playerCaptionsTracklistRenderer")
        val playerCaptionsTracklistRenderer: PlayerCaptionsTracklistRenderer?,
    ) {
        @Serializable
        data class PlayerCaptionsTracklistRenderer(
            @SerialName("captionTracks")
            val captionTracks: List<CaptionTrack>?,
        ) {
            @Serializable
            data class CaptionTrack(
                @SerialName("baseUrl")
                val baseUrl: String?,
                @SerialName("name")
                val name: Name?,
                @SerialName("vssId")
                val vssId: String?,
                @SerialName("languageCode")
                val languageCode: String? = null,
                // "asr" for YouTube's speech recognition, which is always in the spoken (sung) language.
                @SerialName("kind")
                val kind: String? = null,
            ) {
                val isAuto: Boolean get() = kind == "asr" || vssId?.startsWith("a.") == true

                /** The language without its region ("vi" for "vi-VN"), lowercased. */
                val baseLanguage: String? get() = languageCode?.substringBefore('-')?.lowercase()?.takeIf { it.isNotEmpty() }

                @Serializable
                data class Name(
                    @SerialName("simpleText")
                    val simpleText: String?,
                )
            }
        }
    }
}

@Serializable
data class GhostResponse(
    val responseContext: ResponseContext,
    val playbackTracking: PlayerResponse.PlaybackTracking? = null
)