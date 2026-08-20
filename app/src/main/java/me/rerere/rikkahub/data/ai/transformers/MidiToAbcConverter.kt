package me.rerere.rikkahub.data.ai.transformers

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Converts a bounded Standard MIDI File into compact, model-readable ABC notation. */
internal object MidiToAbcConverter {
    private const val MAX_FILE_BYTES = 32L * 1024L * 1024L
    private const val MAX_TRACKS = 128
    private const val MAX_EVENTS = 250_000
    private const val MAX_NOTES = 100_000
    private const val MAX_VOICES = 32
    private const val MAX_OUTPUT_CHARS = 200_000

    fun convert(file: File): Result<String> = runCatching {
        require(file.isFile) { "MIDI file not found" }
        require(file.length() <= MAX_FILE_BYTES) { "MIDI file is larger than 32 MB" }
        parse(file).toAbc(file.name)
    }

    private fun parse(file: File): MidiSong {
        BufferedInputStream(FileInputStream(file), 64 * 1024).use { input ->
            require(readAscii(input, 4) == "MThd") { "Invalid MIDI header" }
            val headerLength = input.readUInt32().toInt()
            require(headerLength >= 6 && headerLength <= 1024) { "Invalid MIDI header length" }
            val header = input.readExact(headerLength)
            val format = header.readUInt16(0)
            val trackCount = header.readUInt16(2)
            val division = header.readUInt16(4)
            require(format in 0..2) { "Unsupported MIDI format" }
            require(trackCount in 1..MAX_TRACKS) { "Unsupported MIDI track count" }
            require(division and 0x8000 == 0 && division > 0) { "SMPTE MIDI timing is not supported" }

            val notes = mutableListOf<Note>()
            var tempoMicrosPerQuarter = 500_000
            var meter = Meter(4, 4)
            var key = KeySignature(0, false)
            var title: String? = null
            var events = 0
            var parsedTracks = 0

            while (parsedTracks < trackCount) {
                val chunkId = readAscii(input, 4) ?: break
                val chunkLength = input.readUInt32()
                require(chunkLength <= Int.MAX_VALUE) { "MIDI chunk is too large" }
                if (chunkId != "MTrk") {
                    input.skipExact(chunkLength)
                    continue
                }

                val track = LimitedInputStream(input, chunkLength)
                val result = parseTrack(track, parsedTracks, notes, events)
                events += result.eventCount
                tempoMicrosPerQuarter = result.tempoMicrosPerQuarter ?: tempoMicrosPerQuarter
                meter = result.meter ?: meter
                key = result.key ?: key
                title = title ?: result.title
                track.drain()
                parsedTracks++
                require(events <= MAX_EVENTS) { "MIDI contains too many events" }
                require(notes.size <= MAX_NOTES) { "MIDI contains too many notes" }
            }
            require(parsedTracks > 0) { "MIDI contains no tracks" }
            require(notes.isNotEmpty()) { "MIDI contains no playable notes" }
            return MidiSong(
                ticksPerQuarter = division,
                notes = notes,
                tempoMicrosPerQuarter = tempoMicrosPerQuarter,
                meter = meter,
                key = key,
                title = title,
            )
        }
    }

    private fun parseTrack(
        input: LimitedInputStream,
        trackIndex: Int,
        notes: MutableList<Note>,
        eventOffset: Int,
    ): TrackResult {
        var tick = 0L
        var runningStatus = -1
        var eventCount = 0
        var tempo: Int? = null
        var meter: Meter? = null
        var key: KeySignature? = null
        var title: String? = null
        val active = HashMap<Int, ArrayDeque<ActiveNote>>()

        while (input.remaining > 0 && eventCount + eventOffset <= MAX_EVENTS) {
            tick += input.readVlq()
            var statusOrData = input.readByteOrThrow()
            val status: Int
            var firstData: Int? = null
            if (statusOrData and 0x80 == 0) {
                require(runningStatus >= 0) { "Invalid running status" }
                status = runningStatus
                firstData = statusOrData
            } else {
                status = statusOrData
                if (status in 0x80..0xEF) runningStatus = status
            }

            when {
                status in 0x80..0x8F -> {
                    val pitch = firstData ?: input.readByteOrThrow()
                    val velocity = input.readByteOrThrow()
                    finishNote(active, status and 0x0F, pitch, tick, trackIndex, notes)
                    if (velocity < 0) error("Invalid MIDI velocity")
                }

                status in 0x90..0x9F -> {
                    val pitch = firstData ?: input.readByteOrThrow()
                    val velocity = input.readByteOrThrow()
                    val channel = status and 0x0F
                    if (velocity == 0) {
                        finishNote(active, channel, pitch, tick, trackIndex, notes)
                    } else {
                        active.getOrPut(channel * 128 + pitch) { ArrayDeque() }
                            .addLast(ActiveNote(tick, channel, pitch, velocity))
                    }
                }

                status in 0xA0..0xBF || status in 0xE0..0xEF -> {
                    if (firstData == null) input.readByteOrThrow()
                    input.readByteOrThrow()
                }

                status in 0xC0..0xDF -> {
                    if (firstData == null) input.readByteOrThrow()
                }

                status == 0xFF -> {
                    val type = input.readByteOrThrow()
                    val length = input.readVlq().toInt()
                    require(length.toLong() <= input.remaining) { "MIDI meta event exceeds track" }
                    val data = input.readExact(length)
                    when (type) {
                        0x2F -> input.drain()
                        0x03 -> title = title ?: data.toString(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
                        0x51 -> if (data.size == 3) {
                            tempo = ((data[0].toInt() and 0xFF) shl 16) or
                                ((data[1].toInt() and 0xFF) shl 8) or
                                (data[2].toInt() and 0xFF)
                        }
                        0x58 -> if (data.size >= 2) {
                            val denominator = 1 shl (data[1].toInt() and 0xFF).coerceIn(0, 7)
                            meter = Meter((data[0].toInt() and 0xFF).coerceIn(1, 32), denominator)
                        }
                        0x59 -> if (data.size >= 2) {
                            key = KeySignature(data[0].toInt(), data[1].toInt() != 0)
                        }
                    }
                }

                status == 0xF0 || status == 0xF7 -> {
                    val length = input.readVlq().toInt()
                    input.skipExact(length.toLong())
                    runningStatus = -1
                }

                status == 0xF1 || status == 0xF3 -> input.readByteOrThrow()
                status == 0xF2 -> {
                    input.readByteOrThrow()
                    input.readByteOrThrow()
                }
                status == 0xF6 -> Unit
                else -> error("Unsupported MIDI status 0x${status.toString(16)}")
            }
            eventCount++
            if (status == 0xFF && input.remaining == 0L) break
        }

        active.values.forEach { pending ->
            while (pending.isNotEmpty()) {
                val note = pending.removeLast()
                if (tick > note.startTick) notes += Note(trackIndex, note.channel, note.pitch, note.startTick, tick, note.velocity)
            }
        }
        return TrackResult(eventCount, tempo, meter, key, title)
    }

    private fun finishNote(
        active: HashMap<Int, ArrayDeque<ActiveNote>>,
        channel: Int,
        pitch: Int,
        endTick: Long,
        track: Int,
        output: MutableList<Note>,
    ) {
        val pending = active[channel * 128 + pitch] ?: return
        val note = pending.removeLastOrNull() ?: return
        if (endTick > note.startTick) {
            output += Note(track, channel, pitch, note.startTick, endTick, note.velocity)
        }
        if (pending.isEmpty()) active.remove(channel * 128 + pitch)
    }

    private fun MidiSong.toAbc(fileName: String): String {
        val unitTicks = ticksPerQuarter.toDouble() / 4.0
        fun quantize(tick: Long): Long = (tick / unitTicks).roundToLong().coerceAtLeast(0)
        val events = notes.map {
            val start = quantize(it.startTick)
            val end = max(start + 1, quantize(it.endTick))
            QuantizedNote(it.track, start, end - start, it.pitch)
        }.groupBy { Triple(it.track, it.start, it.duration) }
            .map { (_, group) -> NoteEvent(group.first().track, group.first().start, group.first().duration, group.map { it.pitch }.distinct().sorted()) }
            .sortedWith(compareBy<NoteEvent> { it.start }.thenBy { it.track }.thenBy { it.pitches.firstOrNull() ?: 0 })

        val voices = mutableListOf<MutableList<NoteEvent>>()
        events.forEach { event ->
            val voice = voices.firstOrNull { voice ->
                val last = voice.lastOrNull()
                last == null || last.start + last.duration <= event.start
            }
            if (voice != null) voice += event
            else if (voices.size < MAX_VOICES) voices += mutableListOf(event)
        }

        val measureUnits = max(1, meter.numerator * 16 / meter.denominator)
        val builder = StringBuilder()
        builder.append("X:1\n")
        builder.append("T:").append(sanitizeHeader(title ?: fileName.substringBeforeLast('.'))).append("\n")
        builder.append("M:").append(meter.numerator).append('/').append(meter.denominator).append("\n")
        builder.append("L:1/16\n")
        builder.append("Q:1/4=").append((60_000_000 / tempoMicrosPerQuarter).coerceIn(1, 999)).append("\n")
        builder.append("K:").append(key.toAbc()).append("\n")

        voices.forEachIndexed { index, voice ->
            builder.append("V:").append(index + 1).append('\n')
            var cursor = 0L
            voice.forEach { event ->
                if (event.start > cursor) appendSpan(builder, "z", cursor, event.start, measureUnits)
                appendSpan(builder, event.pitches.toAbc(), event.start, event.start + event.duration, measureUnits)
                cursor = event.start + event.duration
            }
            if (cursor > 0 && cursor % measureUnits != 0L) builder.append("| ")
            builder.append("\n")
            require(builder.length <= MAX_OUTPUT_CHARS) { "Converted ABC output is too large" }
        }
        return builder.toString().trim()
    }

    private fun appendSpan(builder: StringBuilder, symbol: String, start: Long, end: Long, measureUnits: Int) {
        var cursor = start
        while (cursor < end) {
            val nextBar = ((cursor / measureUnits) + 1) * measureUnits
            val next = min(end, nextBar)
            val duration = (next - cursor).toInt().coerceAtLeast(1)
            builder.append(symbol).append(duration)
            if (next < end) builder.append('-')
            builder.append(' ')
            if (next % measureUnits == 0L) builder.append("| ")
            cursor = next
        }
    }

    private fun List<Int>.toAbc(): String {
        if (size == 1) return pitchToAbc(first())
        return joinToString(prefix = "[", postfix = "]", separator = "") { pitchToAbc(it) }
    }

    private fun pitchToAbc(pitch: Int): String {
        val names = arrayOf("C", "^C", "D", "^D", "E", "F", "^F", "G", "^G", "A", "^A", "B")
        val octave = pitch / 12 - 1
        val base = names[pitch.mod(12)]
        return if (octave >= 5) base.lowercase() + "'".repeat(octave - 5)
        else base + ",".repeat((4 - octave).coerceAtLeast(0))
    }

    private fun KeySignature.toAbc(): String {
        val major = arrayOf("Cb", "Gb", "Db", "Ab", "Eb", "Bb", "F", "C", "G", "D", "A", "E", "B", "F#", "C#")
        val minor = arrayOf("Abm", "Ebm", "Bbm", "Fm", "Cm", "Gm", "Dm", "Am", "Em", "Bm", "F#m", "C#m", "G#m", "D#m", "A#m")
        val index = sf.coerceIn(-7, 7) + 7
        return if (minorMode) minor[index] else major[index]
    }

    private fun sanitizeHeader(value: String): String = value.replace(Regex("[\\r\\n]+"), " ").trim().ifEmpty { "MIDI import" }

    private data class MidiSong(
        val ticksPerQuarter: Int,
        val notes: List<Note>,
        val tempoMicrosPerQuarter: Int,
        val meter: Meter,
        val key: KeySignature,
        val title: String?,
    )

    private data class Note(val track: Int, val channel: Int, val pitch: Int, val startTick: Long, val endTick: Long, val velocity: Int)
    private data class ActiveNote(val startTick: Long, val channel: Int, val pitch: Int, val velocity: Int)
    private data class QuantizedNote(val track: Int, val start: Long, val duration: Long, val pitch: Int)
    private data class NoteEvent(val track: Int, val start: Long, val duration: Long, val pitches: List<Int>)
    private data class Meter(val numerator: Int, val denominator: Int)
    private data class KeySignature(val sf: Int, val minorMode: Boolean)
    private data class TrackResult(val eventCount: Int, val tempoMicrosPerQuarter: Int?, val meter: Meter?, val key: KeySignature?, val title: String?)

    private class LimitedInputStream(private val source: InputStream, var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val value = source.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val count = source.read(buffer, offset, min(length.toLong(), remaining).toInt())
            if (count > 0) remaining -= count
            return count
        }

        override fun skip(n: Long): Long {
            val skipped = source.skip(min(n, remaining))
            remaining -= skipped
            return skipped
        }

        fun drain() {
            val buffer = ByteArray(16 * 1024)
            while (remaining > 0) {
                val read = read(buffer)
                if (read <= 0) error("Unexpected end of MIDI track")
            }
        }
    }

    private fun InputStream.readByteOrThrow(): Int = read().takeIf { it >= 0 } ?: error("Unexpected end of MIDI file")

    private fun InputStream.readUInt32(): Long {
        return (readByteOrThrow().toLong() shl 24) or
            (readByteOrThrow().toLong() shl 16) or
            (readByteOrThrow().toLong() shl 8) or
            readByteOrThrow().toLong()
    }

    private fun ByteArray.readUInt16(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

    private fun InputStream.readVlq(): Long {
        var value = 0L
        repeat(4) { index ->
            val byte = readByteOrThrow()
            value = (value shl 7) or (byte and 0x7F).toLong()
            if (byte and 0x80 == 0) return value
            if (index == 3) error("Invalid MIDI variable-length value")
        }
        return value
    }

    private fun InputStream.readExact(length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = read(result, offset, length - offset)
            if (count <= 0) error("Unexpected end of MIDI file")
            offset += count
        }
        return result
    }

    private fun InputStream.skipExact(length: Long) {
        var remaining = length
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) remaining -= skipped else {
                readByteOrThrow()
                remaining--
            }
        }
    }

    private fun readAscii(input: InputStream, length: Int): String? {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            if (count <= 0) return if (offset == 0) null else error("Unexpected end of MIDI file")
            offset += count
        }
        return bytes.toString(Charsets.US_ASCII)
    }
}
