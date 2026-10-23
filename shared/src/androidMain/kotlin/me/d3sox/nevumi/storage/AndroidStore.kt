package me.d3sox.nevumi.storage

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

fun openMusicStore(context: Context, name: String = "music-player.db"): MusicStore = MusicStore(
    Room.databaseBuilder<MusicDatabase>(context.applicationContext,
        context.getDatabasePath(name).absolutePath)
        .setDriver(BundledSQLiteDriver().withRelaxedDurability())
        // Relaxed durability is only crash-safe with write-ahead logging, so it is never left to Room's automatic choice.
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .addMigrations(RecordRevisionMigration)
        .addCallback(RecordRevisionCallback)
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
)
