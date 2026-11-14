package org.simpmusic.lyrics

import kotlinx.serialization.json.Json
import org.simpmusic.lyrics.models.response.LrclibObject
import org.simpmusic.lyrics.parser.bestLrclibMatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LrclibSelectionTest {
    private fun pick(
        records: List<LrclibObject>,
        duration: Int?,
    ) = bestLrclibMatch(records, "Example", "Example", duration)

    @Test
    fun prefersWordSyncAmongRecordingMatches() {
        val plain = record(1, 137f)
        val rich = record(2, 139f, hasWordSync = true)
        assertEquals(rich, pick(listOf(plain, rich), 137))
    }

    @Test
    fun wordSyncNeverBypassesTheSameRecordingCheck() {
        val outside = record(1, 148f, hasWordSync = true)
        val matching = record(2, 137f)
        assertEquals(matching, pick(listOf(outside, matching), 137))
        assertNull(pick(listOf(outside), 137))

        val otherSong = record(3, 137f, hasWordSync = true).copy(name = "Different", trackName = "Different")
        assertNull(pick(listOf(otherSong), 137))
    }

    @Test
    fun closestLengthBreaksTiesBetweenWordSyncMatches() {
        val far = record(2, 140f, hasWordSync = true)
        val closer = record(3, 137f, hasWordSync = true)
        assertEquals(closer, pick(listOf(far, closer), 137))
    }

    @Test
    fun emptyResultsReturnNull() {
        assertNull(pick(emptyList(), null))
        assertNull(pick(emptyList(), 137))
    }

    @Test
    fun decodesTheServerWordSyncFlag() {
        val source =
            """
            {
              "id": 1, "name": "Example", "trackName": "Example", "artistName": "Example",
              "albumName": null, "duration": 137, "instrumental": false,
              "plainLyrics": null, "syncedLyrics": null, "hasWordSync": true
            }
            """.trimIndent()
        assertEquals(true, Json.decodeFromString<LrclibObject>(source).hasWordSync)
    }

    private fun record(
        id: Int,
        duration: Float,
        hasWordSync: Boolean = false,
    ): LrclibObject =
        LrclibObject(
            id = id,
            name = "Example",
            trackName = "Example",
            artistName = "Example",
            albumName = "Example",
            duration = duration,
            instrumental = false,
            plainLyrics = "Example",
            syncedLyrics = "[00:01.00]Example",
            hasWordSync = hasWordSync,
        )
}
