package com.maxrave.domain.repository

import com.maxrave.domain.data.model.loginsync.DesktopInfo
import com.maxrave.domain.data.model.loginsync.LoginSyncService

/** The Android end of login sync: reads a QR, sends the chosen sign-ins and settings. Bound on Android only. */
interface LoginSyncSenderRepository {
    /** What this phone can send: its sign-ins and the settings groups that apply to it. */
    suspend fun availableServices(): List<LoginSyncService>

    /** Whether [qrText] is a login sync code at all; any other QR is ignored. */
    fun isInvite(qrText: String): Boolean

    /** Reaches the computer behind [qrText]. Throws [com.maxrave.domain.data.model.loginsync.LoginSyncException]. */
    suspend fun connect(qrText: String): DesktopInfo

    /** Sends [services] to the connected computer; returns what it took. Throws LoginSyncException. */
    suspend fun send(services: Set<LoginSyncService>): List<LoginSyncService>

    /**
     * Ends the session and, if the computer is still waiting on this phone, puts it back on its QR.
     * Returns at once — the notice is sent in the background, since callers are teardown paths.
     */
    fun disconnect()
}
