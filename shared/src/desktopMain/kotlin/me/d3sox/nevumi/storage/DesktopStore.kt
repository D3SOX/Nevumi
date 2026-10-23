package me.d3sox.nevumi.storage

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import java.io.File

fun openMusicStore(path: String): MusicStore {
    File(path).absoluteFile.parentFile?.mkdirs()
    return MusicStore(Room.databaseBuilder<MusicDatabase>(name = path)
        .setDriver(BundledSQLiteDriver().withRelaxedDurability())
        // Relaxed durability is only crash-safe with write-ahead logging, so it is never left to Room's automatic choice.
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .addMigrations(RecordRevisionMigration)
        .addCallback(RecordRevisionCallback)
        .setQueryCoroutineContext(Dispatchers.IO)
        .build())
}
