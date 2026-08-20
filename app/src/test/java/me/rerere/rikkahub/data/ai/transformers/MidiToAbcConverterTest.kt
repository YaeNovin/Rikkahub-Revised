package me.rerere.rikkahub.data.ai.transformers

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiToAbcConverterTest {
    @Test
    fun `converts notes and musical metadata to abc`() {
        val file = writeTempMidi(
            header(
                format = 0,
                tracks = 1,
                division = 96,
            ) + track(
                // Tempo 120, 3/4, C major, a C4 quarter note and an E4 quarter note.
                0, 0xFF, 0x51, 0x03, 0x07, 0xA1, 0x20,
                0, 0xFF, 0x58, 0x04, 0x03, 0x02, 0x18, 0x08,
                0, 0xFF, 0x59, 0x02, 0x00, 0x00,
                0, 0x90, 60, 100,
                96, 0x80, 60, 0,
                0, 0x90, 64, 100,
                96, 0x80, 64, 0,
                0, 0xFF, 0x2F, 0x00,
            )
        )

        val abc = MidiToAbcConverter.convert(file).getOrThrow()

        assertTrue(abc.contains("M:3/4"))
        assertTrue(abc.contains("Q:1/4=120"))
        assertTrue(abc.contains("K:C"))
        assertTrue(abc.contains("C4"))
        assertTrue(abc.contains("E4"))
        file.delete()
    }

    @Test
    fun `rejects malformed midi without reading arbitrary binary as text`() {
        val file = File.createTempFile("invalid-midi", ".mid")
        file.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04))

        val result = MidiToAbcConverter.convert(file)

        assertTrue(result.isFailure)
        assertEquals("Invalid MIDI header", result.exceptionOrNull()?.message)
        file.delete()
    }

    private fun writeTempMidi(bytes: ByteArray): File {
        return File.createTempFile("midi", ".mid").also { it.writeBytes(bytes) }
    }

    private fun header(format: Int, tracks: Int, division: Int): ByteArray = byteArrayOf(
        'M'.code.toByte(), 'T'.code.toByte(), 'h'.code.toByte(), 'd'.code.toByte(),
        0, 0, 0, 6,
        (format shr 8).toByte(), format.toByte(),
        (tracks shr 8).toByte(), tracks.toByte(),
        (division shr 8).toByte(), division.toByte(),
    )

    private fun track(vararg values: Int): ByteArray {
        val data = values.map { it.toByte() }.toByteArray()
        return byteArrayOf(
            'M'.code.toByte(), 'T'.code.toByte(), 'r'.code.toByte(), 'k'.code.toByte(),
            (data.size ushr 24).toByte(), (data.size ushr 16).toByte(),
            (data.size ushr 8).toByte(), data.size.toByte(),
        ) + data
    }
}
