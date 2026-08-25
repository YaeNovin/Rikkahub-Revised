package me.rerere.ai.provider.stream

import java.io.EOFException
import me.rerere.ai.provider.isRetryableProviderFailure
import me.rerere.ai.provider.providers.claude.ClaudeStreamDecoder
import me.rerere.ai.provider.providers.google.GoogleStreamDecoder
import me.rerere.ai.provider.providers.openai.ChatCompletionsStreamDecoder
import me.rerere.ai.provider.providers.openai.ResponseApiStreamDecoder
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrematureStreamTerminationTest {
    @Test
    fun `missing terminal event is a retryable premature stream failure`() {
        assertPremature { ChatCompletionsStreamDecoder().onClosed() }
        assertPremature { ResponseApiStreamDecoder().onClosed() }
        assertPremature {
            GoogleStreamDecoder(responseId = "response", model = "gemini-test").onClosed()
        }
        assertPremature { ClaudeStreamDecoder().onClosed() }
    }

    private fun assertPremature(closeStream: () -> Unit) {
        val error = assertThrows(EOFException::class.java) { closeStream() }
        assertTrue(error.isRetryableProviderFailure())
    }
}
