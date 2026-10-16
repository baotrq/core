package com.maxrave.domain.mediaservice.handler

import com.maxrave.domain.mediaservice.handler.AutoMixPick.AudioMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutoMixPickTest {
    private val cMajor120 = AudioMeta(120, "C", "MAJOR")

    @Test
    fun keepsANextTrackThatAlreadyMixes() {
        val next = AudioMeta(122, "G", "MAJOR") // 1.7% + 1 step
        assertNull(AutoMixPick.pick(cMajor120, listOf(next, cMajor120)))
    }

    @Test
    fun movesTheBestFitAheadOfAClash() {
        val clash = AudioMeta(95, "F#", "MAJOR") // 21% + 6 steps
        val ok = AudioMeta(126, "G", "MAJOR") // 5% + 1 step
        val better = AudioMeta(120, "A", "MINOR") // same Camelot number, other letter: 1 step
        assertEquals(2, AutoMixPick.pick(cMajor120, listOf(clash, ok, better)))
    }

    @Test
    fun foldsDoubleTime() {
        val double = AudioMeta(240, "C", "MAJOR")
        assertEquals(0.0, AutoMixPick.cost(cMajor120, double))
    }

    @Test
    fun neverReplacesAnUnknownNextTrack() {
        assertNull(AutoMixPick.pick(cMajor120, listOf(null, cMajor120)))
        assertNull(AutoMixPick.pick(cMajor120, listOf(AudioMeta(null, "C", "MAJOR"), cMajor120)))
    }

    @Test
    fun keepsOrderWhenNothingInReachMixes() {
        val clash = AudioMeta(95, "F#", "MAJOR")
        assertNull(AutoMixPick.pick(cMajor120, listOf(clash, clash, null)))
    }

    @Test
    fun needsTheCurrentTrack() {
        assertNull(AutoMixPick.pick(null, listOf(AudioMeta(95, "F#", "MAJOR"), cMajor120)))
    }
}
