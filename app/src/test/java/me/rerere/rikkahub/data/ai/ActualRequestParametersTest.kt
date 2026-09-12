package me.rerere.rikkahub.data.ai

import org.junit.Assert.*
import org.junit.Test

class ActualRequestParametersTest {
    @Test fun `custom nested parameters survive and credentials are redacted`() {
        val values = actualRequestParameters("""{"temperature":0.7,"custom":{"mode":"precise","api_key":"secret"},"messages":[{"content":"prompt"}]}""")
        assertEquals("0.7", values["body.temperature"])
        assertTrue(values.getValue("body.custom").contains("precise"))
        assertFalse(values.getValue("body.custom").contains("secret"))
        assertFalse(values.containsKey("body.messages"))
    }
}
