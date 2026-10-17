package com.maxrave.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether the stored session — region and language, sign-in cookie, page id, auth user — has reached
 * the YouTube client yet. [CommonRepositoryImpl] copies each one from DataStore in a collector of its
 * own at startup, and nothing ordered those against Home's first request, so a launch could ask for
 * Home signed out or in the default region. Home used to paper over it by loading several times
 * (once a second later, on purpose); now its requests wait for this instead.
 */
internal object YouTubeSession {
    enum class Part { LOCALE, COOKIE, PAGE_ID, AUTH_USER }

    private val pending = MutableStateFlow(Part.entries.toSet())

    fun applied(part: Part) = pending.update { it - part }

    /**
     * Returns once every part has been applied, or after [timeoutMs] — a DataStore that never
     * answers must not keep Home from loading at all.
     */
    suspend fun awaitApplied(timeoutMs: Long = 3_000) {
        withTimeoutOrNull(timeoutMs) { pending.first { it.isEmpty() } }
    }
}
