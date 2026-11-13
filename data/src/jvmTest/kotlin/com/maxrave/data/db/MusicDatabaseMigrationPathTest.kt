package com.maxrave.data.db

import androidx.room.migration.Migration
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Every database version a user can still be holding must be able to reach the current one.
 *
 * Room resolves a path greedily: at each version it takes the largest hop it has and never
 * backtracks, so a missing edge is a dead end rather than a detour — and the only sign of it is a
 * hard crash on the first frame, on the machine of whoever is far enough behind. The three
 * migrations Room cannot write itself (5 -> 6, 10 -> 11, 12 -> 13) had only ever been registered in
 * the Android builder, which left every version from 1 to 12 unreachable on Desktop.
 *
 * [greedyPath] is Room 2.8.5's own `MigrationUtil.findUpMigrationPath`, which is `internal` and so
 * cannot be called from here. Re-check it against the library on a Room upgrade.
 */
class MusicDatabaseMigrationPathTest {
    @Test
    fun `every past version can reach the current one`() {
        val edges = (MusicDatabase_Impl().createAutoMigrations(emptyMap()) + musicDatabaseManualMigrations()).edges()

        for (from in 1 until MUSIC_DATABASE_VERSION) {
            assertNotNull(
                edges.greedyPath(from, MUSIC_DATABASE_VERSION),
                "no migration path from database version $from to $MUSIC_DATABASE_VERSION",
            )
        }
    }

    private fun List<Migration>.edges(): Map<Int, List<Int>> =
        groupBy { it.startVersion }
            .mapValues { (_, migrations) -> migrations.map { it.endVersion }.sortedDescending() }

    private fun Map<Int, List<Int>>.greedyPath(
        start: Int,
        end: Int,
    ): List<Int>? {
        val path = mutableListOf<Int>()
        var current = start
        while (current < end) {
            val next = this[current]?.firstOrNull { it in (current + 1)..end } ?: return null
            path.add(next)
            current = next
        }
        return path
    }
}
