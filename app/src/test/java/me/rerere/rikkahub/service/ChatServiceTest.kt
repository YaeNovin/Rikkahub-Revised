package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.ChatSuggestionAction
import me.rerere.rikkahub.data.model.ChatSuggestionCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatServiceTest {
    @Test fun `suggestion context keeps long response conclusions and describes attachments without their data`() {
        val response = me.rerere.ai.ui.UIMessage.assistant("Opening " + "😀".repeat(2000) + " Final decision")
        val image = me.rerere.ai.ui.UIMessage.user("").copy(parts = listOf(me.rerere.ai.ui.UIMessagePart.Image("data:image/png;base64,PRIVATE")))
        val result = buildSuggestionContext(listOf(image, response))
        assertTrue(result.contains("Opening"))
        assertTrue(result.endsWith("Final decision"))
        assertTrue(result.contains("image=1"))
        assertFalse(result.contains("PRIVATE"))
        assertTrue(result.codePointCount(0, result.length) < 1400)
        assertTrue(result.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == result)
    }

    @Test fun `non string payload is ignored instead of replacing the suggested message`() {
        val item = normalizeGeneratedSuggestionItems("""[{"text":"Useful text","payload":true,"description":42}]""").single()
        assertEquals("", item.payload)
        assertEquals("", item.description)
    }

    @Test fun `invalid structured suggestions never fall back to raw json chips`() {
        for (raw in listOf("""{"error":"No response"}""", """{"suggestions":"wrong type"}""", """{"suggestions":["unfinished""", "null", "123", "true")) {
            assertTrue(raw, normalizeGeneratedSuggestions(raw).isEmpty())
        }
        assertEquals(listOf("Valid"), normalizeGeneratedSuggestions("""[null,123,true,"Valid",{"text":false}]"""))
    }

    @Test fun `blank text can fall back to a valid label without discarding an item`() {
        assertEquals(listOf("Label"), normalizeGeneratedSuggestions("""{"suggestions":[{"text":" ","label":"Label"}]}"""))
    }

    @Test
    fun `generated title removes common model formatting`() {
        assertEquals(
            "会话标题",
            normalizeGeneratedTitle("```text\n标题：\"会话标题\"\n```", maxLength = 24),
        )
        assertEquals("单行标题", normalizeGeneratedTitle("```单行标题```", maxLength = 24))
        assertEquals("未闭合标题", normalizeGeneratedTitle("```text\n未闭合标题", maxLength = 24))
    }

    @Test
    fun `generated title rejects blank output and truncates by code point`() {
        assertNull(normalizeGeneratedTitle("  \n ", maxLength = 24))
        assertEquals("A😀B", normalizeGeneratedTitle("A😀BC", maxLength = 3))
    }

    @Test
    fun `suggestions parse json arrays and remove duplicates`() {
        assertEquals(
            listOf("继续说明", "给个例子"),
            normalizeGeneratedSuggestions(
                raw = "[\"继续说明\", \"给个例子\", \"继续说明\"]",
                maxCount = 5,
                maxLength = 80,
            ),
        )
    }

    @Test
    fun `suggestions normalize markdown numbering and enforce limits`() {
        assertEquals(
            listOf("继续说", "给个例"),
            normalizeGeneratedSuggestions(
                raw = "建议：\n1. 继续说明\n- 给个例子\n3. 不应保留",
                maxCount = 2,
                maxLength = 3,
            ),
        )
    }

    @Test
    fun `suggestions parse supported json object fields`() {
        assertEquals(
            listOf("继续说明", "给个例子"),
            normalizeGeneratedSuggestions(
                raw = """{"suggestions":["继续说明","给个例子"]}""",
                maxCount = 5,
                maxLength = 80,
            ),
        )
    }

    @Test
    fun `structured suggestions preserve safe action metadata`() {
        val result = normalizeGeneratedSuggestionItems(
            raw = """{"suggestions":[{"text":"查找资料","description":"搜索最新来源","category":"action","action":"search_web","payload":"Kotlin 2.2"}]}""",
            maxCount = 5,
            maxLength = 80,
        )

        assertEquals(1, result.size)
        assertEquals("查找资料", result.single().text)
        assertEquals("搜索最新来源", result.single().description)
        assertEquals(ChatSuggestionCategory.ACTION, result.single().category)
        assertEquals(ChatSuggestionAction.SEARCH_WEB, result.single().action)
        assertEquals("Kotlin 2.2", result.single().payload)
    }

    @Test
    fun `unknown suggestion actions fall back to text insertion`() {
        val result = normalizeGeneratedSuggestionItems(
            raw = """{"items":[{"text":"继续","action":"run_arbitrary_command"}]}""",
        )

        assertEquals(ChatSuggestionAction.INSERT_TEXT, result.single().action)
    }

    @Test
    fun `background generation params include model custom request configuration`() {
        val headers = listOf(CustomHeader(name = "X-Gateway-Token", value = "test-token"))
        val bodies = listOf(CustomBody(key = "gateway_mode", value = JsonPrimitive("strict")))
        val model = Model(
            modelId = "custom-chat-model",
            customHeaders = headers,
            customBodies = bodies,
        )

        val params = backgroundTextGenerationParams(model)

        assertEquals(model, params.model)
        assertEquals(ReasoningLevel.AUTO, params.reasoningLevel)
        assertEquals(headers, params.customHeaders)
        assertEquals(bodies, params.customBody)
    }

    @Test
    fun `external web search is disabled when assistant preference is disabled`() {
        val assistant = Assistant(enableWebSearch = false)
        val model = Model()

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `external web search is enabled when assistant preference is enabled`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model()

        assertTrue(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `built-in search suppresses enabled external web search`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model(tools = setOf(BuiltInTools.Search))

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `built-in search remains exclusive when external web search is disabled`() {
        val assistant = Assistant(enableWebSearch = false)
        val model = Model(tools = setOf(BuiltInTools.Search))

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `unrelated built-in tools do not suppress external web search`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model(tools = setOf(BuiltInTools.UrlContext))

        assertTrue(shouldUseExternalWebSearch(assistant, model))
    }
}
