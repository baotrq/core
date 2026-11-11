package com.maxrave.data.loginsync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * What travels inside the loginSync service's sealed payload. The service only moves bytes; these are
 * the sign-ins and settings those bytes mean. Settings are limited to the lists in LoginSyncSettings.kt.
 *
 * The playback-sync blob reuses LoginBundle for its sign-ins but never fills the settings groups.
 */

internal val loginSyncJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class LoginBundle(
    val youtube: YouTubeLogin? = null,
    val spotifySpDc: String? = null,
    val discordToken: String? = null,
    val lastfm: LastfmLogin? = null,
    val aiSettings: SettingsGroup? = null,
    val appSettings: SettingsGroup? = null,
    val playbackSync: SettingsGroup? = null,
)

/**
 * Settings as the phone holds them. [cleared] names the ones the phone has never set, so a value the
 * laptop picked earlier goes back to the default too and the two match after the copy.
 */
@Serializable
internal data class SettingsGroup(
    val values: List<SettingWire> = emptyList(),
    val cleared: List<String> = emptyList(),
)

/** One stored value. [type] keeps the DataStore type, because a key of the wrong type reads as unset. */
@Serializable
internal data class SettingWire(
    val name: String,
    val type: String,
    val value: String,
)

@Serializable
internal data class YouTubeLogin(
    val accounts: List<GoogleAccountWire>,
    val cookie: String,
    val pageId: String?,
    val authUser: Int,
    val dataSyncId: String,
)

/** GoogleAccountEntity on the wire — the Room entity itself is not serializable. */
@Serializable
internal data class GoogleAccountWire(
    val email: String,
    val name: String,
    val thumbnailUrl: String,
    val pageId: String?,
    val authUser: Int,
    val cache: String?,
    val isUsed: Boolean,
    val netscapeCookie: String?,
)

@Serializable
internal data class LastfmLogin(
    val sessionKey: String,
    val username: String,
)

/** Service names rather than the domain enum, so the payload does not depend on its class shape. */
@Serializable
internal data class LoginSyncAck(
    val services: List<String>,
)
