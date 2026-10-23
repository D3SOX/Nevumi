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
    fun reset() { decoded = emptyMap() }
}

internal inline fun <reified T> decodedRecords(json: Json) = DecodedRecords(json, serializer<T>())
