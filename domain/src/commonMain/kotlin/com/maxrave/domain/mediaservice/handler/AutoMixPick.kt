package com.maxrave.domain.mediaservice.handler

import kotlin.math.abs

/**
 * Fork: picks which of the next few radio tracks AutoMix should play next, by how well its tempo
 * and key mix with the playing one.
 *
 * Only a next track KNOWN to clash is ever replaced. One with no BPM data (a video, an upload, a
 * track Tidal does not know) stays where the radio put it: replacing it whenever a better-known
 * track was in reach would hold it back indefinitely.
 *
 * The Camelot mapping repeats the private one in CrossfadeExoPlayerAdapter on purpose: that file is
 * upstream's, and the fork keeps its edits there to a minimum so merges stay clean.
 */
object AutoMixPick {
    data class AudioMeta(
        val bpm: Int?,
        val key: String?,
        val keyScale: String?,
    )

    /** How many upcoming tracks are weighed against each other. */
    const val WINDOW = 5

    /** Cost at or under which two tracks mix well enough that the radio's order is kept. */
    const val GOOD_COST = 9.0

    /**
     * Index into [upcoming] of the track to move to play next, or null to keep the order.
     * [upcoming] starts with the track already next.
     */
    fun pick(
        current: AudioMeta?,
        upcoming: List<AudioMeta?>,
    ): Int? {
        if (current == null || upcoming.size < 2) return null
        val costs = upcoming.map { it?.let { next -> cost(current, next) } }
        val nextCost = costs.first() ?: return null
        if (nextCost <= GOOD_COST) return null
        val best =
            costs
                .withIndex()
                .drop(1)
                .mapNotNull { (i, c) -> c?.let { i to it } }
                .minByOrNull { it.second } ?: return null
        return best.first.takeIf { best.second <= GOOD_COST }
    }

    /**
     * Tempo gap in percent (double/half time folded, as AutoMix does) plus 3 per Camelot step, or
     * null without a BPM on both sides. An unreadable key counts as 2 steps.
     */
    fun cost(
        from: AudioMeta,
        to: AudioMeta,
    ): Double? {
        val a = from.bpm?.takeIf { it > 0 } ?: return null
        val b = to.bpm?.takeIf { it > 0 } ?: return null
        var ratio = b.toDouble() / a
        while (ratio > 1.5) ratio /= 2.0
        while (ratio < 0.67) ratio *= 2.0
        val keySteps =
            run {
                val ca = camelot(from) ?: return@run 2
                val cb = camelot(to) ?: return@run 2
                val diff = abs(ca.first - cb.first)
                minOf(diff, 12 - diff) + if (ca.second != cb.second) 1 else 0
            }
        return abs(1.0 - ratio) * 100 + keySteps * 3
    }

    /** Camelot number and whether it is minor, or null for a key it cannot read. */
    private fun camelot(meta: AudioMeta): Pair<Int, Boolean>? {
        val semitone = semitone(meta.key ?: return null) ?: return null
        val minor = meta.keyScale?.uppercase()?.contains("MIN") == true
        val number = if (minor) MINOR_BY_PITCH[semitone] else MAJOR_BY_PITCH[semitone]
        return number to minor
    }

    private fun semitone(key: String): Int? =
        when (
            key
                .trim()
                .replace("Sharp", "#", ignoreCase = true)
                .replace("Flat", "b", ignoreCase = true)
                .replaceFirstChar { it.uppercaseChar() }
        ) {
            "C" -> 0
            "C#", "Db" -> 1
            "D" -> 2
            "D#", "Eb" -> 3
            "E" -> 4
            "F" -> 5
            "F#", "Gb" -> 6
            "G" -> 7
            "G#", "Ab" -> 8
            "A" -> 9
            "A#", "Bb" -> 10
            "B" -> 11
            else -> null
        }

    private val MINOR_BY_PITCH = intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)
    private val MAJOR_BY_PITCH = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)
}
