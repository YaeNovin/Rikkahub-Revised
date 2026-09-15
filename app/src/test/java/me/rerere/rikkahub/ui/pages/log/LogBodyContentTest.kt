package me.rerere.rikkahub.ui.pages.log

import kotlinx.serialization.json.*
import me.rerere.rikkahub.ui.components.ui.isLongDisplayText
import me.rerere.rikkahub.ui.components.ui.splitDisplayText
import org.junit.Assert.*
import org.junit.Test

class LogBodyContentTest {
    @Test fun `long text is chunked without losing content or splitting surrogate pairs`() {
        val text = ("原文 🌍".repeat(201) + "\n\n").repeat(14)
        val chunks = splitDisplayText(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 1200 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
        assertTrue(isLongDisplayText(text))
        assertTrue(isLongDisplayText("line\n".repeat(18)))
        assertFalse(isLongDisplayText("small"))
    }

    @Test fun `Gemini instructions decode escaped newlines for display without changing JSON`() {
        val source = "line 1\n\nline 2\\path \"quoted\""
        val json = buildJsonObject { putJsonArray("parts") { add(buildJsonObject { put("text", source) }) } }.toString()
        val shown = presentLogBody(json, systemInstruction = true)
        assertEquals(source, shown.text)
        assertEquals("TEXT · systemInstruction", shown.format)
        assertEquals(source, Json.parseToJsonElement(json).jsonObject.getValue("parts").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
    }

    @Test fun `unknown instruction fields stay visible and markup uses actual newlines`() {
        val source = """{"parts":[{"text":"hi","custom":42}],"vendor":true}"""
        assertTrue(presentLogBody(source, true).text.contains("custom"))
        val html = presentLogBody("<p>hi</p><p>there</p>")
        assertTrue(html.text.contains("\n"))
        assertFalse(html.text.contains("\\n"))
        assertEquals("SSE", presentLogBody("data: {}\n\n").format)
    }
}
