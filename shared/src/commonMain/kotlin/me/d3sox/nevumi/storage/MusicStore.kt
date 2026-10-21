package me.d3sox.nevumi.storage

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

@Entity(tableName = "records", indices = [Index("kind")])
data class StoredRecord(@PrimaryKey val key: String, val kind: String, val value: String)

@Entity(tableName = "record_revisions")
data class RecordRevision(@PrimaryKey val kind: String, val revision: Long)

@Dao
interface RecordDao {
    // Rewriting an identical record would still notify every observer of the table, so unchanged values are skipped.
    @Query("INSERT INTO records (`key`, kind, value) VALUES (:key, :kind, :value) ON CONFLICT(`key`) DO UPDATE " +
        "SET kind = excluded.kind, value = excluded.value WHERE kind != excluded.kind OR value != excluded.value")
    suspend fun put(key: String, kind: String, value: String)
    @Query("SELECT value FROM records WHERE `key` = :key") suspend fun read(key: String): String?
    @Query("SELECT * FROM records WHERE kind = :kind ORDER BY `key`")
    suspend fun list(kind: String): List<StoredRecord>
    @Query("SELECT COALESCE((SELECT revision FROM record_revisions WHERE kind = :kind), 0)")
    fun observeRevision(kind: String): Flow<Long>
    // Room invalidates whole tables. Check a small revision before reading a potentially large collection.
    fun observe(kind: String): Flow<List<StoredRecord>> = observeRevision(kind).distinctUntilChanged().map { list(kind) }
    @Query("DELETE FROM records WHERE `key` = :key") suspend fun delete(key: String)
    @Query("DELETE FROM records WHERE kind = :kind") suspend fun deleteKind(kind: String)
}

@Database(entities = [StoredRecord::class, RecordRevision::class], version = 2, exportSchema = true)
@ConstructedBy(MusicDatabaseConstructor::class)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun records(): RecordDao
}

internal object RecordRevisionMigration : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS record_revisions (kind TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL)")
        installRecordRevisionTriggers(connection)
    }
}

internal object RecordRevisionCallback : RoomDatabase.Callback() {
    override fun onCreate(connection: SQLiteConnection) = installRecordRevisionTriggers(connection)
}

private fun installRecordRevisionTriggers(connection: SQLiteConnection) {
    fun increment(kind: String) = "INSERT INTO record_revisions (kind, revision) VALUES ($kind, 1) " +
        "ON CONFLICT(kind) DO UPDATE SET revision = revision + 1;"
    connection.execSQL("CREATE TRIGGER IF NOT EXISTS records_insert_revision AFTER INSERT ON records BEGIN ${increment("NEW.kind")} END")
    connection.execSQL("CREATE TRIGGER IF NOT EXISTS records_delete_revision AFTER DELETE ON records BEGIN ${increment("OLD.kind")} END")
    connection.execSQL("CREATE TRIGGER IF NOT EXISTS records_update_revision AFTER UPDATE ON records " +
        "WHEN OLD.kind != NEW.kind OR OLD.value != NEW.value BEGIN ${increment("OLD.kind")} END")
    connection.execSQL("CREATE TRIGGER IF NOT EXISTS records_move_revision AFTER UPDATE ON records " +
        "WHEN OLD.kind != NEW.kind BEGIN ${increment("NEW.kind")} END")
}

@Suppress("KotlinNoActualForExpect")
expect object MusicDatabaseConstructor : RoomDatabaseConstructor<MusicDatabase> {
    override fun initialize(): MusicDatabase
}

class MusicStore internal constructor(internal val database: MusicDatabase) {
    internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    internal suspend inline fun <reified T> read(key: String): T? =
        database.records().read(key)?.let { json.decodeFromString<T>(it) }
    internal suspend inline fun <reified T> put(key: String, kind: String, value: T) {
        database.records().put(key, kind, json.encodeToString(value))
    }
    internal suspend inline fun <reified T> list(kind: String): List<T> =
        database.records().list(kind).map { json.decodeFromString<T>(it.value) }
    internal suspend fun <T> transaction(block: suspend () -> T): T =
        database.useWriterConnection { connection -> connection.immediateTransaction { block() } }
    fun close() = database.close()
}
