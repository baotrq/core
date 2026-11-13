package com.maxrave.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.maxrave.common.DB_NAME
import org.koin.mp.KoinPlatform.getKoin

actual fun getDatabaseBuilder(converters: Converters): RoomDatabase.Builder<MusicDatabase> =
    Room
        .databaseBuilder(getKoin().get(), MusicDatabase::class.java, DB_NAME)
        .addTypeConverter(converters)
        .addMigrations(*musicDatabaseManualMigrations().toTypedArray())
        .addCallback(
            object : RoomDatabase.Callback() {
                override fun onOpen(connection: SQLiteConnection) {
                    super.onOpen(connection)
                    connection.execSQL(
                        "CREATE TRIGGER  IF NOT EXISTS on_delete_pair_song_local_playlist AFTER DELETE ON pair_song_local_playlist\n" +
                            "FOR EACH ROW\n" +
                            "BEGIN\n" +
                            "    UPDATE pair_song_local_playlist\n" +
                            "    SET position = position - 1\n" +
                            "    WHERE playlistId = OLD.playlistId AND position > OLD.position;\n" +
                            "END;",
                    )
                }
            },
        )

actual fun getDatabasePath(): String = getKoin().get<Context>().getDatabasePath(DB_NAME).path
