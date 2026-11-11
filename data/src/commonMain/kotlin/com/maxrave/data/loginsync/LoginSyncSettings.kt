package com.maxrave.data.loginsync

import com.maxrave.common.SELECTED_LANGUAGE
import com.maxrave.common.SponsorBlockType

/*
 * The settings Login Sync may copy, by name. An allowlist on purpose: a setting added later stays on
 * its own device until someone decides it belongs on both. The receiver checks these lists too, so a
 * value outside them is dropped whatever the sender put in the payload.
 *
 * Left out because they belong to one device: car mode, LED strip, Smart downloads, liked-song
 * downloads, cache limits, auto backup, kill service on exit, mobile-data quality, equalizer type
 * (Android's system equalizer), liquid glass (its cost differs by machine), the lyrics offset (it
 * corrects this device's audio path), the proxy, and the sync device name and id.
 * Left out because they are running state, not choices: saved queue, shuffle and repeat, volume, speed
 * and pitch, tokens and caches, update checks, one-time prompts.
 */

/** The AI provider, its key and what the key cannot work without. */
internal val AI_SETTING_NAMES =
    setOf(
        "ai_provider",
        "ai_gemini_api_key",
        "custom_model_id",
        "custom_openai_base_url",
        "custom_openai_headers",
        "use_ai_translation",
    )

internal const val AI_API_KEY_NAME = "ai_gemini_api_key"

internal val APP_SETTING_NAMES =
    setOf(
        SELECTED_LANGUAGE,
        "location",
        "theme_mode",
        "home_limit",
        "chart_key",
        "local_playlist_filter",
        "now_playing_style",
        // Without this the laptop's first launch rewrites an APPLE_MUSIC style it was just sent.
        "now_playing_style_default_done",
        "lyrics_style",
        "lyrics_provider",
        "translation_language",
        "use_translation_language",
        "romanization_languages",
        "help_build_lyrics_database",
        "contributor_name",
        "contributor_email",
        "spotify_lyrics",
        "spotify_canvas",
        "am_animated_artwork",
        "quality",
        "download_quality",
        "video_quality",
        "video_download_quality",
        "normalize_volume",
        "skip_silent",
        "save_state_of_playback",
        "save_recent_song",
        "send_back_to_google",
        "watch_video_instead_of_playing_audio",
        "radio_audio_only",
        "endless_queue",
        "explicit_content_enabled",
        "preferred_audio_language",
        "youtube_subtitle_language",
        "crossfade_enabled",
        "crossfade_duration",
        "crossfade_dj_mode",
        "crossfade_skip_album",
        "equalizer_enabled",
        "equalizer_bands",
        "equalizer_preamp",
        "equalizer_autoeq_profile",
        "delay_enabled",
        "delay_time_ms",
        "delay_feedback",
        "delay_mix",
        "reverb_enabled",
        "reverb_preset",
        "reverb_mix",
        "sponsor_block_enabled",
        "sync_follow_to_youtube",
        "keep_youtube_playlist_offline",
        "combine_local_and_youtube_liked",
        "rich_presence",
        "lastfm_scrobble_enabled",
        "local_tracking_enabled",
    ) + SponsorBlockType.toList().map { it.value }

internal const val PLAYBACK_SYNC_URL_NAME = "playback_sync_url"
internal const val PLAYBACK_SYNC_KEY_NAME = "playback_sync_key"

/** Written by Settings when the hidden tools are unlocked; gates sending the server at all. */
internal const val PERSONAL_TOOLS_NAME = "personal_tools_shown"

/**
 * The private sync server. Sent only from a phone with the hidden tools unlocked, and the unlock comes
 * along, or the server's rows would stay hidden on the laptop. Never the device name or id: a shared id
 * would make the two devices one to playback sync.
 */
internal val PLAYBACK_SYNC_SETTING_NAMES =
    setOf(
        PLAYBACK_SYNC_URL_NAME,
        PLAYBACK_SYNC_KEY_NAME,
        "playback_sync_enabled",
        PERSONAL_TOOLS_NAME,
    )
