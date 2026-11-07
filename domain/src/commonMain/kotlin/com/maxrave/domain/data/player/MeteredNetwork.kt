package com.maxrave.domain.data.player

/**
 * Whether the device is on a metered connection right now, for choosing a stream's quality.
 *
 * Only the Android app installs a check. Everywhere else this stays false, so Desktop never reads
 * the mobile-data quality.
 */
object MeteredNetwork {
    @Volatile
    var check: () -> Boolean = { false }
}
