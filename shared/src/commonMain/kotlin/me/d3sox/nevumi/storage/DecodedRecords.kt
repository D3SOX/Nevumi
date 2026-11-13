package me.d3sox.nevumi.storage

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/** The result of one [DecodedRecords.update]: every record of the kind and the ones whose stored value changed. */
internal class RecordUpdate<T>(val all: List<T>, val changed: List<T>)

/**
 * Decodes a kind of record incrementally. Most reads of a kind follow a write to one record, so only records whose
 * stored JSON changed since the previous call are decoded again; the rest keep their earlier objects.
 */
internal class DecodedRecords<T>(private val json: Json, private val serializer: KSerializer<T>) {
    private var decoded: Map<String, Pair<String, T>> = emptyMap()
    private var readRevision: Long? = null
    private var readDeletions: Long? = null

    /**
     * Updates from the store, reading only the records of [kind] written since the previous read, and their keys only after a
     * deletion. Reading every record of a large kind, such as the listening history, on each change cost a full table read.
     */
    suspend fun read(store: MusicStore, kind: String): RecordUpdate<T> {
        val records = store.database.records()
        val since = readRevision
        // A transaction may roll back after this read, so a read inside one starts over the next time.
        if (since == null || store.inTransaction()) {
            val revision = records.revision(kind)
            val deletions = records.revision("deleted:$kind")
            return update(records.list(kind)).also {
                readRevision = if (store.inTransaction()) null else revision
                readDeletions = deletions
            }
        }
        val revision = records.revision(kind)
        val deletions = records.revision("deleted:$kind")
        val next = LinkedHashMap(decoded)
        val changed = mutableListOf<T>()
        // A read canceled halfway must not leave a newer revision with older records.
        readRevision = null
        for (record in records.changedSince(kind, since)) {
            if (next[record.key]?.first == record.value) continue
            val value = json.decodeFromString(serializer, record.value)
            next[record.key] = record.value to value
            changed += value
        }
        if (deletions != readDeletions) next.keys.retainAll(records.keys(kind).toHashSet())
        decoded = next
        readRevision = revision
        readDeletions = deletions
        return RecordUpdate(next.values.map { it.second }, changed)
    }

    fun update(records: List<StoredRecord>): RecordUpdate<T> {
        val previous = decoded
        val changed = mutableListOf<T>()
        val next = LinkedHashMap<String, Pair<String, T>>(records.size)
        val all = ArrayList<T>(records.size)
        for (record in records) {
            val entry = previous[record.key]?.takeIf { it.first == record.value }
                ?: (record.value to json.decodeFromString(serializer, record.value)).also { changed += it.second }
            next[record.key] = entry
            all += entry.second
        }
        decoded = next
        return RecordUpdate(all, changed)
    }

    /** Forgets the previous decodes, so the next update reports every record as changed. */
    fun reset() { decoded = emptyMap(); readRevision = null }
}

internal inline fun <reified T> decodedRecords(json: Json) = DecodedRecords(json, serializer<T>())
