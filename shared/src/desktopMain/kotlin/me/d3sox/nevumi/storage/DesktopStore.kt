package me.d3sox.nevumi.storage

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import java.io.File

fun openMusicStore(path: String): MusicStore {
    File(path).absoluteFile.parentFile?.mkdirs()
    return MusicStore(Room.databaseBuilder<MusicDatabase>(name = path)
        .setDriver(BundledSQLiteDriver())
        .addMigrations(RecordRevisionMigration)
        .addCallback(RecordRevisionCallback)
        .setQueryCoroutineContext(Dispatchers.IO)
        .build())
}
