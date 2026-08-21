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
    private const val MAX_FILE_BYTES = 128L * 1024L * 1024L
    private const val MAX_TRACKS = 128
    private const val MAX_EVENTS = 300_000
    private const val MAX_NOTES = 50_000
    private const val MAX_ACTIVE_NOTES = 8_192
    private const val MAX_VOICES = 32
    private const val MAX_OUTPUT_CHARS = 120_000
    private const val MAX_TITLE_BYTES = 4_096
    private const val TRUNCATION_SUFFIX_RESERVE = 160

    fun convert(file: File): Result<String> = runCatching {
        require(file.isFile) { "MIDI file not found" }
        require(file.length() <= MAX_FILE_BYTES) { "MIDI file is larger than 128 MB" }
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
            require(trackCount > 0) { "MIDI contains no tracks" }
            require(division and 0x8000 == 0 && division > 0) { "SMPTE MIDI timing is not supported" }

            val notes = mutableListOf<Note>()
            var tempoMicrosPerQuarter = 500_000
            var meter = Meter(4, 4)
            var key = KeySignature(0, false)
            var title: String? = null
            var events = 0
            var parsedTracks = 0
            var truncated = trackCount > MAX_TRACKS
            val trackLimit = min(trackCount, MAX_TRACKS)

            while (parsedTracks < trackLimit) {
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
                truncated = truncated || result.truncated
                tempoMicrosPerQuarter = result.tempoMicrosPerQuarter ?: tempoMicrosPerQuarter
                meter = result.meter ?: meter
                key = result.key ?: key
                title = title ?: result.title
                track.drain()
                parsedTracks++
                if (result.stoppedEarly) break
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
                truncated = truncated || parsedTracks < trackCount,
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
        var truncated = false
        var stoppedEarly = false
        val active = HashMap<Int, ArrayDeque<ActiveNote>>()
        var activeNoteCount = 0

        while (input.remaining > 0) {
            if (eventCount + eventOffset >= MAX_EVENTS) {
                truncated = true
                stoppedEarly = true
                input.drain()
                break
            }
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
                    val pitch = firstData ?: input.readMidiDataByte()
                    input.readMidiDataByte()
                    if (finishNote(active, status and 0x0F, pitch, tick, trackIndex, notes)) {
                        activeNoteCount--
                    }
                }

                status in 0x90..0x9F -> {
                    val pitch = firstData ?: input.readMidiDataByte()
                    val velocity = input.readMidiDataByte()
                    val channel = status and 0x0F
                    if (velocity == 0) {
                        if (finishNote(active, channel, pitch, tick, trackIndex, notes)) {
                            activeNoteCount--
                        }
                    } else if (activeNoteCount < MAX_ACTIVE_NOTES && notes.size < MAX_NOTES) {
                        active.getOrPut(channel * 128 + pitch) { ArrayDeque() }
                            .addLast(ActiveNote(tick, channel, pitch, velocity))
                        activeNoteCount++
                    } else {
                        truncated = true
                    }
                }

                status in 0xA0..0xBF || status in 0xE0..0xEF -> {
                    if (firstData == null) input.readMidiDataByte()
                    input.readMidiDataByte()
                }

                status in 0xC0..0xDF -> {
                    if (firstData == null) input.readMidiDataByte()
                }

                status == 0xFF -> {
                    val type = input.readByteOrThrow()
                    val length = input.readVlq()
                    require(length <= input.remaining) { "MIDI meta event exceeds track" }
                    when (type) {
                        0x2F -> {
                            input.skipExact(length)
                            input.drain()
                        }
                        0x03 -> {
                            val titleLength = min(length, MAX_TITLE_BYTES.toLong()).toInt()
                            val data = input.readExact(titleLength)
                            input.skipExact(length - titleLength)
                            if (length > MAX_TITLE_BYTES) truncated = true
                            title = title ?: data.toString(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
                        }
                        0x51 -> if (length == 3L) {
                            val data = input.readExact(3)
                            tempo = ((data[0].toInt() and 0xFF) shl 16) or
                                ((data[1].toInt() and 0xFF) shl 8) or
                                (data[2].toInt() and 0xFF)
                        } else input.skipExact(length)
                        0x58 -> if (length >= 2L) {
                            val data = input.readExact(2)
                            input.skipExact(length - 2L)
                            val denominator = 1 shl (data[1].toInt() and 0xFF).coerceIn(0, 7)
                            meter = Meter((data[0].toInt() and 0xFF).coerceIn(1, 32), denominator)
                        } else input.skipExact(length)
                        0x59 -> if (length >= 2L) {
                            val data = input.readExact(2)
                            input.skipExact(length - 2L)
                            key = KeySignature(data[0].toInt(), data[1].toInt() != 0)
                        } else input.skipExact(length)
                        else -> input.skipExact(length)
                    }
                }

                status == 0xF0 || status == 0xF7 -> {
                    val length = input.readVlq()
                    require(length <= input.remaining) { "MIDI SysEx event exceeds track" }
                    input.skipExact(length)
                    runningStatus = -1
                }

                status == 0xF1 || status == 0xF3 -> {
                    input.readMidiDataByte()
                    runningStatus = -1
                }
                status == 0xF2 -> {
                    input.readMidiDataByte()
                    input.readMidiDataByte()
                    runningStatus = -1
                }
                status == 0xF4 || status == 0xF5 || status == 0xF6 -> runningStatus = -1
                status in 0xF8..0xFE -> Unit
                else -> error("Unsupported MIDI status 0x${status.toString(16)}")
            }
            eventCount++
            if (notes.size >= MAX_NOTES && input.remaining > 0L) {
                truncated = true
                stoppedEarly = true
                input.drain()
                break
            }
            if (status == 0xFF && input.remaining == 0L) break
        }

        active.values.forEach { pending ->
            while (pending.isNotEmpty()) {
                val note = pending.removeLast()
                if (tick > note.startTick && notes.size < MAX_NOTES) {
                    notes += Note(trackIndex, note.channel, note.pitch, note.startTick, tick, note.velocity)
                } else if (tick > note.startTick) {
                    truncated = true
                }
            }
        }
        return TrackResult(eventCount, tempo, meter, key, title, truncated, stoppedEarly)
    }

    private fun finishNote(
        active: HashMap<Int, ArrayDeque<ActiveNote>>,
        channel: Int,
        pitch: Int,
        endTick: Long,
        track: Int,
        output: MutableList<Note>,
    ): Boolean {
        val pending = active[channel * 128 + pitch] ?: return false
        val note = pending.removeLastOrNull() ?: return false
        if (endTick > note.startTick && output.size < MAX_NOTES) {
            output += Note(track, channel, pitch, note.startTick, endTick, note.velocity)
        }
        if (pending.isEmpty()) active.remove(channel * 128 + pitch)
        return true
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
        var truncated = this.truncated
        events.forEach { event ->
            val voice = voices.firstOrNull { voice ->
                val last = voice.lastOrNull()
                last == null || last.start + last.duration <= event.start
            }
            if (voice != null) voice += event
            else if (voices.size < MAX_VOICES) voices += mutableListOf(event)
            else truncated = true
        }

        val measureUnits = max(1, meter.numerator * 16 / meter.denominator)
        val builder = StringBuilder()
        builder.append("X:1\n")
        builder.append("T:").append(sanitizeHeader(title ?: fileName.substringBeforeLast('.'))).append("\n")
        builder.append("M:").append(meter.numerator).append('/').append(meter.denominator).append("\n")
        builder.append("L:1/16\n")
        builder.append("Q:1/4=").append((60_000_000 / tempoMicrosPerQuarter).coerceIn(1, 999)).append("\n")
        builder.append("K:").append(key.toAbc()).append("\n")

        voiceLoop@ for ((index, voice) in voices.withIndex()) {
            if (!builder.appendBounded("V:${index + 1}\n")) {
                truncated = true
                break
            }
            var cursor = 0L
            for (event in voice) {
                if (event.start > cursor && !appendSpan(builder, "z", cursor, event.start, measureUnits)) {
                    truncated = true
                    break@voiceLoop
                }
                if (!appendSpan(builder, event.pitches.toAbc(), event.start, event.start + event.duration, measureUnits)) {
                    truncated = true
                    break@voiceLoop
                }
                cursor = event.start + event.duration
            }
            if (cursor > 0 && cursor % measureUnits != 0L && !builder.appendBounded("| ")) {
                truncated = true
                break
            }
            if (!builder.appendBounded("\n")) {
                truncated = true
                break
            }
        }
        if (truncated) {
            builder.append("\n% Conversion truncated to a bounded musical preview for reliable processing.\n")
        }
        return builder.toString().trim()
    }

    private fun appendSpan(builder: StringBuilder, symbol: String, start: Long, end: Long, measureUnits: Int): Boolean {
        var cursor = start
        while (cursor < end) {
            val nextBar = ((cursor / measureUnits) + 1) * measureUnits
            val next = min(end, nextBar)
            val duration = (next - cursor).toInt().coerceAtLeast(1)
            val token = buildString {
                append(symbol).append(duration)
                if (next < end) append('-')
                append(' ')
                if (next % measureUnits == 0L) append("| ")
            }
            if (!builder.appendBounded(token)) return false
            cursor = next
        }
        return true
    }

    private fun StringBuilder.appendBounded(value: String): Boolean {
        if (length + value.length > MAX_OUTPUT_CHARS - TRUNCATION_SUFFIX_RESERVE) return false
        append(value)
        return true
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
        val truncated: Boolean,
    )

    private data class Note(val track: Int, val channel: Int, val pitch: Int, val startTick: Long, val endTick: Long, val velocity: Int)
    private data class ActiveNote(val startTick: Long, val channel: Int, val pitch: Int, val velocity: Int)
    private data class QuantizedNote(val track: Int, val start: Long, val duration: Long, val pitch: Int)
    private data class NoteEvent(val track: Int, val start: Long, val duration: Long, val pitches: List<Int>)
    private data class Meter(val numerator: Int, val denominator: Int)
    private data class KeySignature(val sf: Int, val minorMode: Boolean)
    private data class TrackResult(
        val eventCount: Int,
        val tempoMicrosPerQuarter: Int?,
        val meter: Meter?,
        val key: KeySignature?,
        val title: String?,
        val truncated: Boolean,
        val stoppedEarly: Boolean,
    )

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
            while (remaining > 0) {
                if (skip(remaining) <= 0L && read() < 0) {
                    error("Unexpected end of MIDI track")
                }
            }
        }
    }

    private fun InputStream.readByteOrThrow(): Int = read().takeIf { it >= 0 } ?: error("Unexpected end of MIDI file")

    private fun InputStream.readMidiDataByte(): Int {
        while (true) {
            val value = readByteOrThrow()
            if (value in 0xF8..0xFE) continue
            require(value and 0x80 == 0) { "Invalid MIDI data byte" }
            return value
        }
    }

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
