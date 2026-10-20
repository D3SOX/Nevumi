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
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

@Entity(tableName = "records", indices = [Index("kind")])
data class StoredRecord(@PrimaryKey val key: String, val kind: String, val value: String)

@Dao
interface RecordDao {
    // Rewriting an identical record would still notify every observer of the table, so unchanged values are skipped.
    @Query("INSERT INTO records (`key`, kind, value) VALUES (:key, :kind, :value) ON CONFLICT(`key`) DO UPDATE " +
        "SET kind = excluded.kind, value = excluded.value WHERE kind != excluded.kind OR value != excluded.value")
    suspend fun put(key: String, kind: String, value: String)
    @Query("SELECT value FROM records WHERE `key` = :key") suspend fun read(key: String): String?
    @Query("SELECT * FROM records WHERE kind = :kind ORDER BY `key`")
    suspend fun list(kind: String): List<StoredRecord>
    @Query("SELECT * FROM records WHERE kind = :kind ORDER BY `key`")
    fun observe(kind: String): Flow<List<StoredRecord>>
    @Query("DELETE FROM records WHERE `key` = :key") suspend fun delete(key: String)
    @Query("DELETE FROM records WHERE kind = :kind") suspend fun deleteKind(kind: String)
}

@Database(entities = [StoredRecord::class], version = 1, exportSchema = true)
@ConstructedBy(MusicDatabaseConstructor::class)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun records(): RecordDao
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
