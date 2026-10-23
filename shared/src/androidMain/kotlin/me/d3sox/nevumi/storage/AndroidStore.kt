package me.d3sox.nevumi.storage

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

fun openMusicStore(context: Context, name: String = "music-player.db"): MusicStore = MusicStore(
    Room.databaseBuilder<MusicDatabase>(context.applicationContext,
        context.getDatabasePath(name).absolutePath)
        .setDriver(BundledSQLiteDriver().withRelaxedDurability())
        .addMigrations(RecordRevisionMigration)
        .addCallback(RecordRevisionCallback)
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
)
