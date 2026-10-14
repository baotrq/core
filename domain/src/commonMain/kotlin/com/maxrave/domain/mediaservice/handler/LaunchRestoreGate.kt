package com.maxrave.domain.mediaservice.handler

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fork: lets the app finish something before the queue saved on this device is loaded at launch.
 * Playback sync uses it to write another phone's newer queue there first, so the mini player never
 * shows this device's old song and then switches. Null unless the app sets it, so nothing waits.
 */
object LaunchRestoreGate {
    /** Longest the launch restore waits, so a stuck gate can never leave the player empty. */
    const val MAX_WAIT_MS = 4_000L

    @Volatile
    var pending: Deferred<Unit>? = null

    suspend fun await() {
        pending?.let { withTimeoutOrNull(MAX_WAIT_MS) { it.await() } }
    }
}
