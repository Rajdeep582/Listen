package com.rajdeep.listen.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SongEntity::class, SongStatsEntity::class, VideoEntity::class, VideoStateEntity::class,
        PlaylistEntity::class, PlaylistSongEntity::class, HistoryEntity::class, LyricsEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ListenDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        fun build(context: Context): ListenDatabase =
            Room.databaseBuilder(context, ListenDatabase::class.java, "listen.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
