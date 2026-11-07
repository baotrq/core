package org.simpmusic.lyrics.parser

import org.simpmusic.lyrics.models.response.LrclibObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtraLyricsParsersTest {
    private fun same(
        candidateTitle: String,
        candidateArtist: String,
        title: String,
        artist: String,
        candidateDuration: Double? = 200.0,
        duration: Int? = 200,
    ) = isSameRecording(candidateTitle, candidateArtist, candidateDuration, title, artist, duration, 3.0)

    @Test
    fun titleCleaningKeepsWhatNamesADifferentRecording() {
        assertEquals("Dracula", "Dracula (feat. JENNIE)".forLyricsSearch())
        assertEquals("Blinding Lights", "Blinding Lights (Official Music Video)".forLyricsSearch())
        assertEquals("Song", "Song ft. Someone".forLyricsSearch())
        assertEquals("Song (Remix)", "Song (Remix)".forLyricsSearch())
        assertEquals("The Weeknd", "The Weeknd - Topic".artistForLyricsSearch())
    }

    @Test
    fun theSameRecordingMatchesAcrossSpellings() {
        assertTrue(same("Blinding Lights", "The Weeknd", "Blinding Lights", "The Weeknd"))
        assertTrue(same("The Weeknd - Blinding Lights", "The Weeknd", "Blinding Lights", "The Weeknd"))
        assertTrue(same("Blinding Lights", "The Weeknd", "The Weeknd - Blinding Lights", "The Weeknd"))
        assertTrue(same("Chung Ta Cua Hien Tai", "Son Tung MTP", "Chúng Ta Của Hiện Tại", "Sơn Tùng M-TP"))
        assertTrue(same("Song", "Sơn Tùng M-TP, Snoop Dogg", "Song", "Sơn Tùng M-TP"))
        assertTrue(same("Bohemian Rhapsody (Remastered 2011)", "Queen", "Bohemian Rhapsody", "Queen"))
    }

    @Test
    fun anythingElseIsNotTheRecording() {
        // A real Megalobiz answer for this query.
        assertFalse(same("Không Phải Tại Chúng Mình", "Lê Sang", "Chúng Ta Của Hiện Tại", "Sơn Tùng M-TP"))
        assertFalse(same("Blinding Lights", "Someone Else", "Blinding Lights", "The Weeknd"))
        assertFalse(same("Blinding Lights", "", "Blinding Lights", "The Weeknd"))
        assertFalse(same("Blinding Lights (Remix)", "The Weeknd", "Blinding Lights", "The Weeknd"))
        assertFalse(same("Bohemian Rhapsody (Live)", "Queen", "Bohemian Rhapsody", "Queen"))
        assertFalse(same("Blinding Lights", "The Weeknd", "Blinding Lights", "The Weeknd", candidateDuration = 263.0))
        assertFalse(same("Blinding Lights", "The Weeknd", "Blinding Lights", "The Weeknd", candidateDuration = null))
        // Only when we do not know our own length is there nothing to compare.
        assertTrue(same("Blinding Lights", "The Weeknd", "Blinding Lights", "The Weeknd", candidateDuration = null, duration = null))
    }

    @Test
    fun lrclibTakesTheClosestTimedEntryNotTheFirst() {
        fun entry(
            id: Int,
            title: String,
            duration: Float,
            synced: String?,
        ) = LrclibObject(id, title, title, "The Weeknd", null, duration, false, "plain", synced)

        val results =
            listOf(
                entry(1, "The Weeknd - Blinding Lights", 203f, "[00:01.00]a"),
                entry(2, "Blinding Lights", 200f, "[00:01.00]b"),
                entry(3, "Blinding Lights", 200f, null),
                entry(4, "Other Song", 200f, "[00:01.00]c"),
            )
        assertEquals(2, bestLrclibMatch(results, "Blinding Lights", "The Weeknd", 200)?.id)
    }

    @Test
    fun lrcRedSkipsOtherCutsOfTheSong() {
        // lrc.red's real first hits for this query.
        val json =
            """{"hits":[
            {"isrc":"GBUM71029604","title":"Bohemian Rhapsody (Remastered 2011)","artist":"Queen","duration":356.52},
            {"isrc":"GBUM71100986","title":"Bohemian Rhapsody (Operatic Section / 2011 A Cappella Mix)","artist":"Queen","duration":64.969},
            {"isrc":"GBCEG0100046","title":"Bohemian Rhapsody (Live)","artist":"Queen","duration":352.04},
            {"isrc":"../../etc","title":"Bohemian Rhapsody","artist":"Queen","duration":356.0}
            ]}"""
        assertEquals("GBUM71029604", lrcRedIsrc(json, "Bohemian Rhapsody", "Queen", 355))
        assertNull(lrcRedIsrc(json, "Bohemian Rhapsody", "Queen", 200))
    }

    @Test
    fun kugouKeepsOnlyThisRecording() {
        val json =
            """{"data":{"info":[
            {"hash":"cover","duration":200,"songname":"Blinding Lights","singername":"Someone Else"},
            {"hash":"other","duration":200,"songname":"Save Your Tears","singername":"The Weeknd"},
            {"hash":"far","duration":203,"songname":"Blinding Lights","singername":"The Weeknd"},
            {"hash":"near","duration":201,"songname":"Blinding Lights","singername":"The Weeknd"}
            ]}}"""
        assertEquals(listOf("near", "far"), kugouSongHashes(json, "Blinding Lights", "The Weeknd", 200))
    }

    @Test
    fun creditsAtBothEndsAreCutAndALyricColonStays() {
        val lrc =
            """
            [ti:Song]
            [00:00.00]Song - Artist
            [00:01.75]Lyrics by：Someone
            [00:03.00]Composed by：Someone
            [00:10.00]first line
            [00:20.00]she said: stay
            [00:30.00]last line
            [03:00.00]Produced by：Someone
            """.trimIndent()
        val cut = stripKugouCredits(lrc)
        assertFalse(cut.contains("Composed by"))
        assertFalse(cut.contains("Produced by"))
        assertTrue(cut.contains("first line"))
        assertTrue(cut.contains("last line"))
    }

    @Test
    fun instrumentalNoticeIsNotLyrics() {
        assertTrue(isInstrumentalNotice("[00:00.00]纯音乐，请欣赏"))
        assertFalse(isInstrumentalNotice("[00:00.00]real words"))
    }

    @Test
    fun megalobizPicksTheClosestMatchingSheet() {
        val json =
            """{"results":[
            {"id":1,"title":"Blinding Lights","artist":"The Weeknd","duration":201.61},
            {"id":2,"title":"blinding lights","artist":"the weeknd","duration":200.87},
            {"id":3,"title":"Something Else","artist":"The Weeknd","duration":200.1},
            {"id":4,"title":"Blinding Lights","artist":"The Weeknd","duration":240.0}
            ]}"""
        assertEquals("2", megalobizSheetId(json, "Blinding Lights", "The Weeknd", 200))
    }

    @Test
    fun lrcFractionsAreHundredthsOrThousandths() {
        fun firstStart(lrc: String) =
            parseSyncedLyrics(lrc)
                .lyrics
                ?.lines
                ?.single()
                ?.startTimeMs
        assertEquals("10500", firstStart("[00:10.50]hello"))
        assertEquals("10123", firstStart("[00:10.123]hello"))
    }

    @Test
    fun ttmlSyllablesOfOneWordStayOneWord() {
        val ttml =
            """<p begin="31.0" end="33.0"><span begin="31.5" end="31.8">long</span> <span begin="31.8" end="32.0">e</span><span begin="32.0" end="32.5">nough</span></p>"""
        val words =
            parseTtmlLyrics(ttml)
                .lyrics
                ?.lines
                ?.single()
                ?.words
        assertEquals("<00:31.50>long <00:31.80>enough", words)
    }
}
