package me.d3sox.nevumi.youtube

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Rewrites a fragmented MP4, as YouTube serves its audio, into a plain one with a single sample table. Tag editors, including
 * the one Nevumi uses, and many players only handle the plain layout. The audio itself is copied unchanged.
 */
internal object Mp4Defragmenter {
    private class Box(val type: String, val start: Long, val size: Long, val headerSize: Int) {
        val payloadStart get() = start + headerSize
        val end get() = start + size
    }

    private class Run(val dataStart: Long, val sizes: IntArray, val durations: IntArray)

    fun defragment(input: File, output: File) = RandomAccessFile(input, "r").use { file ->
        val top = boxes(file, 0, file.length())
        val moov = top.singleOrNull { it.type == "moov" } ?: throw IOException("The audio has no MP4 header")
        val moovChildren = boxes(file, moov.payloadStart, moov.end)
        val trak = moovChildren.singleOrNull { it.type == "trak" } ?: throw IOException("Only MP4 files with one track can be converted")
        val trackChildren = boxes(file, trak.payloadStart, trak.end)
        val mdia = trackChildren.first { it.type == "mdia" }
        val mdiaChildren = boxes(file, mdia.payloadStart, mdia.end)
        val minf = mdiaChildren.first { it.type == "minf" }
        val minfChildren = boxes(file, minf.payloadStart, minf.end)
        val stbl = minfChildren.first { it.type == "stbl" }
        val stblChildren = boxes(file, stbl.payloadStart, stbl.end)
        val trex = moovChildren.firstOrNull { it.type == "mvex" }?.let { mvex -> boxes(file, mvex.payloadStart, mvex.end).firstOrNull { it.type == "trex" } }
        // trex: version and flags, track ID, sample description index, default duration, default size, default flags.
        val trexDuration = trex?.let { read(file, it.payloadStart + 12, 4).int(0) } ?: 0
        val trexSize = trex?.let { read(file, it.payloadStart + 16, 4).int(0) } ?: 0
        val runs = top.filter { it.type == "moof" }.flatMap { moof -> fragmentRuns(file, moof, trexDuration, trexSize) }
        if (runs.isEmpty()) throw IOException("The audio has no samples")
        val sampleCount = runs.sumOf { it.sizes.size }
        val mediaDuration = runs.sumOf { run -> run.durations.sumOf { it.toLong() and 0xFFFFFFFFL } }
        val mdhd = read(file, mdiaChildren.first { it.type == "mdhd" })
        val mvhd = read(file, moovChildren.first { it.type == "mvhd" })
        val movieDuration = mediaDuration * timescale(mvhd) / timescale(mdhd)

        fun table(offsets: LongArray): ByteArray {
            val stts = ByteArrayOutputStream()
            val durations = runs.flatMap { it.durations.asList() }
            val groups = mutableListOf<Pair<Int, Int>>()
            durations.forEach { duration -> if (groups.lastOrNull()?.second == duration) groups[groups.lastIndex] = groups.last().first + 1 to duration else groups += 1 to duration }
            DataOutputStream(stts).apply { writeInt(0); writeInt(groups.size); groups.forEach { (count, duration) -> writeInt(count); writeInt(duration) } }
            val stsc = ByteArrayOutputStream()
            val chunks = mutableListOf<Pair<Int, Int>>()
            runs.forEachIndexed { index, run -> if (chunks.lastOrNull()?.second != run.sizes.size) chunks += index + 1 to run.sizes.size }
            DataOutputStream(stsc).apply { writeInt(0); writeInt(chunks.size); chunks.forEach { (first, samples) -> writeInt(first); writeInt(samples); writeInt(1) } }
            val stsz = ByteArrayOutputStream()
            DataOutputStream(stsz).apply { writeInt(0); writeInt(0); writeInt(sampleCount); runs.forEach { run -> run.sizes.forEach(::writeInt) } }
            val stco = ByteArrayOutputStream()
            DataOutputStream(stco).apply { writeInt(0); writeInt(offsets.size); offsets.forEach { writeInt(it.toInt()) } }
            val kept = stblChildren.filter { it.type == "stsd" }.map { read(file, it) }
            return box("stbl", *kept.toTypedArray(), box("stts", stts.toByteArray()), box("stsc", stsc.toByteArray()),
                box("stsz", stsz.toByteArray()), box("stco", stco.toByteArray()))
        }

        fun header(offsets: LongArray): ByteArray {
            val minfBox = box("minf", *minfChildren.map { if (it.type == "stbl") table(offsets) else read(file, it) }.toTypedArray())
            val mdiaBox = box("mdia", *mdiaChildren.map {
                when (it.type) { "minf" -> minfBox; "mdhd" -> withDuration(mdhd, 24, 32, mediaDuration); else -> read(file, it) }
            }.toTypedArray())
            // The edit list skips the encoder's priming samples. One with an open-ended entry only fits fragments, so it is left out.
            val trakBox = box("trak", *trackChildren.filter { it.type != "edts" || closedEdits(file, it) }.map {
                when (it.type) { "mdia" -> mdiaBox; "tkhd" -> withDuration(read(file, it), 28, 36, movieDuration); else -> read(file, it) }
            }.toTypedArray())
            return box("moov", *moovChildren.filter { it.type != "mvex" }.map {
                when (it.type) { "trak" -> trakBox; "mvhd" -> withDuration(mvhd, 24, 32, movieDuration); else -> read(file, it) }
            }.toTypedArray())
        }

        val ftyp = box("ftyp", "M4A ".encodeToByteArray(), ByteArray(4), "M4A mp42isom".encodeToByteArray())
        val dataBytes = runs.sumOf { run -> run.sizes.sumOf { it.toLong() } }
        // The chunk offsets change the header's size only through their count, so a first pass with zeros measures it.
        val headerSize = header(LongArray(runs.size)).size
        val dataStart = ftyp.size + headerSize + 8L
        if (dataStart + dataBytes > 0xFFFFFFFFL) throw IOException("The audio is too long to convert")
        var next = dataStart
        val offsets = LongArray(runs.size) { index -> next.also { next += runs[index].sizes.sumOf { it.toLong() } } }
        output.outputStream().buffered().use { out ->
            out.write(ftyp)
            out.write(header(offsets))
            DataOutputStream(out).apply { writeInt((dataBytes + 8).toInt()); writeBytes("mdat") }.flush()
            val buffer = ByteArray(64 * 1024)
            runs.forEach { run ->
                var remaining = run.sizes.sumOf { it.toLong() }
                file.seek(run.dataStart)
                while (remaining > 0) {
                    val count = file.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) throw IOException("The audio ended early")
                    out.write(buffer, 0, count)
                    remaining -= count
                }
            }
        }
    }

    /** Whether every entry of the edit list in [edts] has a length, which a plain file needs. */
    private fun closedEdits(file: RandomAccessFile, edts: Box): Boolean {
        val elst = boxes(file, edts.payloadStart, edts.end).singleOrNull { it.type == "elst" } ?: return false
        val bytes = read(file, elst)
        val wide = bytes[8].toInt() == 1
        val count = bytes.int(12)
        val entrySize = if (wide) 20 else 12
        if (count <= 0 || 16 + count.toLong() * entrySize > bytes.size) return false
        return (0 until count).all { index ->
            val at = 16 + index * entrySize
            (if (wide) bytes.long(at) else bytes.int(at).toLong() and 0xFFFFFFFFL) > 0
        }
    }

    private fun fragmentRuns(file: RandomAccessFile, moof: Box, trexDuration: Int, trexSize: Int): List<Run> =
        boxes(file, moof.payloadStart, moof.end).filter { it.type == "traf" }.flatMap { traf ->
            val children = boxes(file, traf.payloadStart, traf.end)
            val tfhd = read(file, children.first { it.type == "tfhd" })
            val tfhdFlags = tfhd.int(8) and 0xFFFFFF
            var at = 16
            val baseOffset = if (tfhdFlags and 0x1 != 0) tfhd.long(at).also { at += 8 } else moof.start
            if (tfhdFlags and 0x2 != 0) at += 4
            val defaultDuration = if (tfhdFlags and 0x8 != 0) tfhd.int(at).also { at += 4 } else trexDuration
            val defaultSize = if (tfhdFlags and 0x10 != 0) tfhd.int(at).also { at += 4 } else trexSize
            children.filter { it.type == "trun" }.map { trunBox ->
                val trun = read(file, trunBox)
                val flags = trun.int(8) and 0xFFFFFF
                val count = trun.int(12)
                var position = 16
                val dataOffset = if (flags and 0x1 != 0) trun.int(position).also { position += 4 } else 0
                if (flags and 0x4 != 0) position += 4
                val durations = IntArray(count)
                val sizes = IntArray(count)
                for (index in 0 until count) {
                    durations[index] = if (flags and 0x100 != 0) trun.int(position).also { position += 4 } else defaultDuration
                    sizes[index] = if (flags and 0x200 != 0) trun.int(position).also { position += 4 } else defaultSize
                    if (flags and 0x400 != 0) position += 4
                    if (flags and 0x800 != 0) position += 4
                }
                if (sizes.any { it <= 0 }) throw IOException("The audio has samples of unknown size")
                Run(baseOffset + dataOffset, sizes, durations)
            }
        }

    private fun boxes(file: RandomAccessFile, start: Long, end: Long): List<Box> {
        val found = mutableListOf<Box>()
        var position = start
        while (position + 8 <= end) {
            val head = read(file, position, 8)
            var size = head.int(0).toLong() and 0xFFFFFFFFL
            var headerSize = 8
            if (size == 1L) { size = read(file, position + 8, 8).long(0); headerSize = 16 }
            else if (size == 0L) size = end - position
            if (size < headerSize || position + size > end) throw IOException("The audio's MP4 structure is damaged")
            found += Box(head.decodeToString(4, 8), position, size, headerSize)
            position += size
        }
        return found
    }

    private fun read(file: RandomAccessFile, box: Box): ByteArray {
        if (box.size > 64L * 1024 * 1024) throw IOException("The audio's MP4 header is too large")
        return read(file, box.start, box.size.toInt())
    }

    private fun read(file: RandomAccessFile, position: Long, count: Int) = ByteArray(count).also { file.seek(position); file.readFully(it) }

    /** Sets a full box's duration, which version 0 stores as 32 bits at [short] and version 1 as 64 bits at [long]. */
    private fun withDuration(box: ByteArray, short: Int, long: Int, duration: Long): ByteArray = box.copyOf().apply {
        if (this[8].toInt() == 1) for (i in 0 until 8) this[long + i] = (duration ushr (56 - 8 * i)).toByte()
        else { val value = duration.coerceAtMost(0xFFFFFFFFL); for (i in 0 until 4) this[short + i] = (value ushr (24 - 8 * i)).toByte() }
    }

    /** The timescale of an mvhd or mdhd box. */
    private fun timescale(box: ByteArray): Long = (if (box[8].toInt() == 1) box.int(28) else box.int(20)).toLong() and 0xFFFFFFFFL

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val size = 8 + parts.sumOf { it.size }
        return ByteArrayOutputStream(size).also { out ->
            DataOutputStream(out).apply { writeInt(size); writeBytes(type) }
            parts.forEach(out::write)
        }.toByteArray()
    }

    private fun ByteArray.int(at: Int) = ((this[at].toInt() and 0xFF) shl 24) or ((this[at + 1].toInt() and 0xFF) shl 16) or
        ((this[at + 2].toInt() and 0xFF) shl 8) or (this[at + 3].toInt() and 0xFF)
    private fun ByteArray.long(at: Int) = ((int(at).toLong() and 0xFFFFFFFFL) shl 32) or (int(at + 4).toLong() and 0xFFFFFFFFL)
}
