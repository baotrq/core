package com.maxrave.data.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.maxrave.domain.data.entities.PairSongLocalPlaylist
import com.maxrave.domain.data.entities.SetVideoIdEntity
import com.maxrave.logger.Logger
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlin.time.ExperimentalTime

/**
 * The schema version [MusicDatabase] is currently at.
 *
 * Named so the migration-path test can see it; bump it with every schema change, as before.
 */
const val MUSIC_DATABASE_VERSION: Int = 27

/**
 * The migrations Room cannot write by itself, and therefore the gaps in the [AutoMigration] chain
 * declared on [MusicDatabase]: 5 -> 6, 10 -> 11 and 12 -> 13.
 *
 * They live here, in commonMain, because **every** platform builder has to register them. Room
 * resolves a path greedily — at each version it takes the largest hop it has and never backtracks —
 * so a single missing edge is not routed around, it is a dead end. These three edges were only ever
 * added in the Android builder, which left every database between version 1 and 12 unreachable on
 * Desktop: the app opened such a file and died on the first frame with
 * "A migration from <n> to 27 was required but not found". A Desktop install that old, or one
 * holding a restored backup from an old Android install, could not be started again at all.
 *
 * Keep them shared. Adding the next one to one builder only is exactly how this broke.
 */
@OptIn(ExperimentalTime::class)
internal fun musicDatabaseManualMigrations(): List<Migration> {
    val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    return listOf(
        object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                val playlistSongMaps = mutableListOf<PairSongLocalPlaylist>()
                connection.prepare("SELECT * FROM local_playlist").use { cursor ->
                    while (cursor.step()) {
                        if (!cursor.isNull(8)) {
                            val input = cursor.getText(8)
                            val tracks =
                                json.decodeFromString<ArrayList<String?>?>(input)
                            Logger.d("MIGRATION_5_6", "tracks: ${tracks?.size}")
                            tracks?.mapIndexed { index, track ->
                                if (track != null) {
                                    playlistSongMaps.add(
                                        PairSongLocalPlaylist(
                                            playlistId = cursor.getLong(0),
                                            songId = track,
                                            position = index,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
                connection.execSQL("ALTER TABLE `format` ADD COLUMN `lengthSeconds` INTEGER DEFAULT NULL")
                connection.execSQL("ALTER TABLE `format` ADD COLUMN `youtubeCaptionsUrl` TEXT DEFAULT NULL")
                connection.execSQL("ALTER TABLE `format` ADD COLUMN `cpn` TEXT DEFAULT NULL")
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pair_song_local_playlist` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlistId` INTEGER NOT NULL, `songId` TEXT NOT NULL, `position` INTEGER NOT NULL, `inPlaylist` INTEGER NOT NULL, FOREIGN KEY(`playlistId`) REFERENCES `local_playlist`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`songId`) REFERENCES `song`(`videoId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pair_song_local_playlist_playlistId` ON `pair_song_local_playlist` (`playlistId`)",
                )
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_pair_song_local_playlist_songId` ON `pair_song_local_playlist` (`songId`)")
                playlistSongMaps.forEach { pair ->
                    connection.execSQL(
                        "INSERT OR IGNORE INTO pair_song_local_playlist (playlistId, songId, position, inPlaylist) VALUES (${pair.playlistId}, '${pair.songId}', ${pair.position}, ${pair.inPlaylist.toInstant(TimeZone.UTC).toEpochMilliseconds()})",
                    )
                }
            }
        },
        object : Migration(10, 11) {
            override fun migrate(connection: SQLiteConnection) {
                val listYouTubeSyncedId = mutableListOf<Pair<String, List<String>>>() // Pair<youtubePlaylistId, listVideoId>
                connection
                    .prepare(
                        "SELECT youtubePlaylistId, tracks FROM local_playlist WHERE synced_with_youtube_playlist = 1 AND youtubePlaylistId NOT NULL",
                    ).use { cursor ->
                        while (cursor.step()) {
                            val youtubePlaylistId = cursor.getText(0)
                            val input = cursor.getText(1)
                            val tracks =
                                json.decodeFromString<ArrayList<String?>?>(input)
                            listYouTubeSyncedId.add(Pair(youtubePlaylistId, tracks?.toMutableList()?.filterNotNull() ?: emptyList()))
                        }
                    }
                val setVideoIdList = mutableListOf<SetVideoIdEntity>()
                connection.prepare("SELECT * FROM set_video_id").use { cursor ->
                    while (cursor.step()) {
                        val videoId = cursor.getText(0)
                        val setVideoId = cursor.getText(1)
                        for (pair in listYouTubeSyncedId) {
                            if (pair.second.contains(videoId)) {
                                setVideoIdList.add(SetVideoIdEntity(videoId, setVideoId, pair.first))
                                break
                            }
                        }
                    }
                }
                connection.execSQL("DROP TABLE set_video_id")
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `set_video_id` (`videoId` TEXT NOT NULL, `setVideoId` TEXT, `youtubePlaylistId` TEXT NOT NULL, PRIMARY KEY(`videoId`, `youtubePlaylistId`))",
                )
                setVideoIdList.forEach { setVideoIdEntity ->
                    connection.execSQL(
                        "INSERT OR IGNORE INTO set_video_id (videoId, setVideoId, youtubePlaylistId) VALUES ('${setVideoIdEntity.videoId}', '${setVideoIdEntity.setVideoId}', '${setVideoIdEntity.youtubePlaylistId}')",
                    )
                }
            }
        },
        object : Migration(12, 13) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE song ADD COLUMN canvasUrl TEXT")
            }
        },
    )
}
