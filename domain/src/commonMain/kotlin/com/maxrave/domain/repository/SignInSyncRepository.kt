package com.maxrave.domain.repository

/**
 * Fork: this device's sign-ins (YouTube, Spotify, Discord, Last.fm) as an opaque string, for the
 * app's cloud sync to carry between the user's own devices. What the string means stays in data.
 */
interface SignInSyncRepository {
    /** The services signed in here. */
    suspend fun export(): String

    /**
     * Signs in to what [exported] carries that is new here, and returns how many services that was.
     * Only ever adds or refreshes: a service missing or signed out in [exported] is left as it is.
     */
    suspend fun import(exported: String): Int
}
