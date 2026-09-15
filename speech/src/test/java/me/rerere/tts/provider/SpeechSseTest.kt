package me.rerere.tts.provider

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class SpeechSseTest {
    @Test fun slowConsumerReceivesEveryEventBeyondTypicalChannelCapacity() = runBlocking {
        val source = Buffer().writeUtf8((1..200).joinToString("") { "data: $it\n\n" })
        val received = mutableListOf<Int>()
        readSpeechEvents(source).collect { delay(1); received.add(it.data.toInt()) }
        assertEquals((1..200).toList(), received)
    }
    @Test fun handlesHeartbeatMultilineAndLastEventWithoutTrailingBlankLine() = runBlocking {
        val source = Buffer().writeUtf8("\uFEFF: heartbeat\r\nid: 1\r\nevent: audio\r\ndata: first\r\ndata: second\r\n\r\ndata: final")
        val events = readSpeechEvents(source).toList()
        assertEquals(2, events.size)
        assertEquals("first\nsecond", events[0].data)
        assertEquals("audio", events[0].type)
        assertEquals("final", events[1].data)
        assertNull(events[1].type)
    }
}
