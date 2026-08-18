package me.rerere.ai.provider

import me.rerere.ai.ui.StreamChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderProtocolTraceTest {
    @Test
    fun `protocol trace round trips and validates its recorded semantics`() {
        val trace = ProviderProtocolTraceRecorder.record(
            protocol = "openai",
            modelId = "custom-model",
            chunks = listOf(
                StreamChunk.TextStart("message-1"),
                StreamChunk.TextDelta("message-1", "OK"),
                StreamChunk.TextEnd("message-1"),
                StreamChunk.Finish(model = "custom-model"),
            ),
        )

        val decoded = ProviderProtocolTraceCodec.decode(ProviderProtocolTraceCodec.encode(trace))

        assertEquals(trace, decoded)
        assertTrue(ProviderProtocolTraceReplayer.validate(decoded).passed)
    }

    @Test
    fun `protocol regression fails when the stream semantics change`() {
        val trace = ProviderProtocolTraceRecorder.record(
            protocol = "openai",
            modelId = "custom-model",
            chunks = listOf(
                StreamChunk.ToolCallStart("tool-1", "diagnostic_ping"),
                StreamChunk.ToolCallEnd("tool-1"),
                StreamChunk.Finish(),
            ),
        )

        val changedTrace = trace.copy(chunks = trace.chunks.dropLast(1))

        assertFalse(ProviderProtocolTraceReplayer.validate(changedTrace).passed)
    }

    @Test
    fun `protocol recorder redacts credential shaped values`() {
        val trace = ProviderProtocolTraceRecorder.record(
            protocol = "openai",
            modelId = "sk-secret-model-token",
            chunks = listOf(
                StreamChunk.TextDelta("message-1", "Authorization: Bearer sk-secret-value"),
                StreamChunk.Finish(),
            ),
        )

        val serialized = ProviderProtocolTraceCodec.encode(trace)

        assertFalse(serialized.contains("secret-value"))
        assertFalse(serialized.contains("secret-model-token"))
        assertTrue(serialized.contains("[REDACTED]"))
    }
}
