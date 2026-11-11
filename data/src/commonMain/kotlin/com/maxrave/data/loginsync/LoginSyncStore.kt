package com.maxrave.data.loginsync

import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.io.fileDir
import com.maxrave.domain.data.entities.GoogleAccountEntity
import com.maxrave.domain.data.model.loginsync.LoginSyncService
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.manager.DataStoreManager.Values.TRUE
import com.maxrave.domain.repository.CommonRepository
import com.maxrave.logger.Logger
import kotlinx.coroutines.flow.first

/** Reads this device's sign-ins, and writes a received bundle in the same shape the login screens do. */
internal class LoginSyncStore(
    private val dataStore: DataStoreManager,
    private val localDataSource: LocalDataSource,
    private val commonRepository: CommonRepository,
) {
    suspend fun signedIn(): List<LoginSyncService> =
        buildList {
            if (dataStore.loggedIn.first() == TRUE && dataStore.cookie.first().isNotEmpty()) add(LoginSyncService.YOUTUBE)
            if (dataStore.spdc.first().isNotEmpty()) add(LoginSyncService.SPOTIFY)
            if (dataStore.discordToken.first().isNotEmpty()) add(LoginSyncService.DISCORD)
            if (dataStore.lastfmSessionKey.first().isNotEmpty()) add(LoginSyncService.LASTFM)
        }

    /** What this phone can offer over the QR. Separate from [signedIn], which playback sync exports. */
    suspend fun available(): List<LoginSyncService> =
        buildList {
            addAll(signedIn())
            if (!dataStore.getString(AI_API_KEY_NAME).first().isNullOrBlank()) add(LoginSyncService.AI_SETTINGS)
            add(LoginSyncService.APP_SETTINGS)
            val toolsUnlocked = dataStore.getString(PERSONAL_TOOLS_NAME).first() == TRUE
            val hasServer =
                !dataStore.getString(PLAYBACK_SYNC_URL_NAME).first().isNullOrBlank() &&
                    !dataStore.getString(PLAYBACK_SYNC_KEY_NAME).first().isNullOrBlank()
            if (toolsUnlocked && hasServer) add(LoginSyncService.PLAYBACK_SYNC)
        }

    private suspend fun settingsGroup(names: Set<String>): SettingsGroup {
        val stored = dataStore.readPreferences(names)
        return SettingsGroup(
            values = stored.mapNotNull { (name, value) -> value.toWire(name) },
            cleared = (names - stored.keys).toList(),
        )
    }

    /** Writes [group] in one edit, keeping only names in [allowed] whatever the payload holds. */
    private suspend fun applySettings(
        group: SettingsGroup,
        allowed: Set<String>,
    ) {
        dataStore.writePreferences(
            values = group.values.filter { it.name in allowed }.mapNotNull { it.toValue() }.toMap(),
            removed = group.cleared.filter { it in allowed }.toSet(),
        )
    }

    suspend fun bundle(services: Set<LoginSyncService>): LoginBundle =
        LoginBundle(
            youtube =
                if (LoginSyncService.YOUTUBE in services) {
                    YouTubeLogin(
                        accounts = localDataSource.getGoogleAccounts().map { it.toWire() },
                        cookie = dataStore.cookie.first(),
                        pageId = dataStore.pageId.first().ifEmpty { null },
                        authUser = dataStore.authUser.first(),
                        dataSyncId = dataStore.dataSyncId.first(),
                    )
                } else {
                    null
                },
            spotifySpDc = if (LoginSyncService.SPOTIFY in services) dataStore.spdc.first() else null,
            discordToken = if (LoginSyncService.DISCORD in services) dataStore.discordToken.first() else null,
            lastfm =
                if (LoginSyncService.LASTFM in services) {
                    LastfmLogin(dataStore.lastfmSessionKey.first(), dataStore.lastfmUsername.first())
                } else {
                    null
                },
            aiSettings = if (LoginSyncService.AI_SETTINGS in services) settingsGroup(AI_SETTING_NAMES) else null,
            appSettings = if (LoginSyncService.APP_SETTINGS in services) settingsGroup(APP_SETTING_NAMES) else null,
            playbackSync = if (LoginSyncService.PLAYBACK_SYNC in services) settingsGroup(PLAYBACK_SYNC_SETTING_NAMES) else null,
        )

    /** Writes [bundle] over this device's sign-ins and returns the services it carried. */
    suspend fun restore(bundle: LoginBundle): List<LoginSyncService> =
        buildList {
            bundle.youtube?.let {
                restoreYouTube(it)
                add(LoginSyncService.YOUTUBE)
            }
            bundle.spotifySpDc?.takeIf { it.isNotEmpty() }?.let { spDc ->
                dataStore.setSpdc(spDc)
                // A personal token minted for the previous sp_dc would otherwise stay in use until it
                // expires — the same reset the Spotify logout does.
                dataStore.setSpotifyPersonalToken("")
                dataStore.setSpotifyPersonalTokenExpires(0)
                add(LoginSyncService.SPOTIFY)
            }
            bundle.discordToken?.takeIf { it.isNotEmpty() }?.let {
                dataStore.setDiscordToken(it)
                add(LoginSyncService.DISCORD)
            }
            bundle.lastfm?.takeIf { it.sessionKey.isNotEmpty() }?.let {
                dataStore.setLastfmSession(sessionKey = it.sessionKey, username = it.username)
                add(LoginSyncService.LASTFM)
            }
            bundle.aiSettings?.let {
                applySettings(it, AI_SETTING_NAMES)
                add(LoginSyncService.AI_SETTINGS)
            }
            bundle.appSettings?.let {
                applySettings(it, APP_SETTING_NAMES)
                add(LoginSyncService.APP_SETTINGS)
            }
            bundle.playbackSync?.let {
                applySettings(it, PLAYBACK_SYNC_SETTING_NAMES)
                add(LoginSyncService.PLAYBACK_SYNC)
            }
        }

    /**
     * Mirrors SettingsViewModel.setUsedAccount. Order matters: the account rows go in BEFORE the
     * cookie, because CommonRepositoryImpl rewrites the yt-dlp cookie file from the used account the
     * moment the cookie changes — written the other way round it would write the old account's file.
     */
    private suspend fun restoreYouTube(login: YouTubeLogin) {
        localDataSource.getGoogleAccounts().forEach { localDataSource.updateGoogleAccountUsed(it.email, false) }
        login.accounts.forEach { localDataSource.insertGoogleAccount(it.toEntity()) }
        val used = login.accounts.firstOrNull { it.isUsed }
        dataStore.putString("AccountName", used?.name.orEmpty())
        dataStore.putString("AccountThumbUrl", used?.thumbnailUrl.orEmpty())
        used?.netscapeCookie?.let { commonRepository.writeTextToFile(it, "${fileDir()}/ytdlp-cookie.txt") }
        if (login.dataSyncId.isNotEmpty()) dataStore.setDataSyncId(login.dataSyncId)
        dataStore.setCookie(login.cookie, login.pageId, login.authUser)
        dataStore.setLoggedIn(true)
        Logger.i("Auth", "YouTube: signed in through Login Sync, ${login.accounts.size} account(s)")
    }
}

private fun Any.toWire(name: String): SettingWire? =
    when (this) {
        is String -> SettingWire(name, "string", this)
        is Int -> SettingWire(name, "int", toString())
        is Long -> SettingWire(name, "long", toString())
        is Float -> SettingWire(name, "float", toString())
        else -> null
    }

private fun SettingWire.toValue(): Pair<String, Any>? {
    val decoded: Any? =
        when (type) {
            "string" -> value
            "int" -> value.toIntOrNull()
            "long" -> value.toLongOrNull()
            "float" -> value.toFloatOrNull()
            else -> null
        }
    return decoded?.let { name to it }
}

private fun GoogleAccountEntity.toWire() =GoogleAccountWire(email, name, thumbnailUrl, pageId, authUser, cache, isUsed, netscapeCookie)

private fun GoogleAccountWire.toEntity() =
    GoogleAccountEntity(
        email = email,
        name = name,
        thumbnailUrl = thumbnailUrl,
        pageId = pageId,
        authUser = authUser,
        cache = cache,
        isUsed = isUsed,
        netscapeCookie = netscapeCookie,
    )
