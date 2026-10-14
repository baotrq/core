package com.maxrave.data.loginsync

import com.maxrave.domain.repository.SignInSyncRepository

/** Reuses Login Sync's bundle, so a sign-in is written in the same shape and order the login screens use. */
internal class SignInSyncRepositoryImpl(
    private val store: LoginSyncStore,
) : SignInSyncRepository {
    override suspend fun export(): String = loginSyncJson.encodeToString(LoginBundle.serializer(), local())

    override suspend fun import(exported: String): Int {
        val remote = loginSyncJson.decodeFromString(LoginBundle.serializer(), exported)
        val local = local()
        // Only what differs here, so an unchanged sign-in is not written again (and not re-uploaded).
        val fresh =
            LoginBundle(
                youtube = remote.youtube?.takeIf { it.cookie.isNotEmpty() && it.cookie != local.youtube?.cookie },
                spotifySpDc = remote.spotifySpDc?.takeIf { it.isNotEmpty() && it != local.spotifySpDc },
                discordToken = remote.discordToken?.takeIf { it.isNotEmpty() && it != local.discordToken },
                lastfm = remote.lastfm?.takeIf { it.sessionKey.isNotEmpty() && it.sessionKey != local.lastfm?.sessionKey },
            )
        return store.restore(fresh).size
    }

    private suspend fun local(): LoginBundle = store.bundle(store.signedIn().toSet())
}
