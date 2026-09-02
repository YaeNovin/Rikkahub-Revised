package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolArgumentJsonTest {
    @Test
    fun `tool arguments preserve strings objects and arrays`() {
        assertEquals("{\"command\":\"ls\"}", buildJsonObject {
            put("command", "ls")
        }.toToolArgumentString())
        assertEquals("[1,2]", buildJsonArray {
            add(JsonPrimitive(1))
            add(JsonPrimitive(2))
        }.toToolArgumentString())
        assertEquals("{}", JsonPrimitive("{}").toToolArgumentString())
    }
}
