package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.GeminiGenerationOptions
import me.rerere.ai.provider.GeminiMediaResolution
import me.rerere.ai.provider.GeminiResponseMimeType
import me.rerere.ai.provider.GeminiSafetySettings
import me.rerere.ai.provider.GeminiSafetyThreshold
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleGeminiOptionsRequestTest {
    private val provider = GoogleProvider(OkHttpClient())

    @Test
    fun `maps supported assistant options into Gemini 3 request`() {
        val body = buildRequest(
            modelId = "gemini-3.1-pro-preview",
            options = GeminiGenerationOptions(
                includeThoughts = false,
                mediaResolution = GeminiMediaResolution.HIGH,
                seed = 42,
                stopSequences = listOf("END", "DONE"),
                responseMimeType = GeminiResponseMimeType.JSON,
                responseJsonSchema = """{"type":"object","properties":{"answer":{"type":"string"}}}""",
                presencePenalty = 0.25f,
                frequencyPenalty = -0.5f,
                safetySettings = GeminiSafetySettings(
                    harassment = GeminiSafetyThreshold.BLOCK_ONLY_HIGH,
                    hateSpeech = GeminiSafetyThreshold.BLOCK_MEDIUM_AND_ABOVE,
                    sexuallyExplicit = GeminiSafetyThreshold.BLOCK_LOW_AND_ABOVE,
                    dangerousContent = GeminiSafetyThreshold.BLOCK_NONE,
                ),
            ),
        )

        val generation = body["generationConfig"]!!.jsonObject
        assertFalse(generation.containsKey("temperature"))
        assertFalse(generation.containsKey("topP"))
        assertEquals("MEDIA_RESOLUTION_HIGH", generation["mediaResolution"]!!.jsonPrimitive.content)
        assertEquals(42, generation["seed"]!!.jsonPrimitive.int)
        assertEquals(
            listOf("END", "DONE"),
            generation["stopSequences"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("application/json", generation["responseMimeType"]!!.jsonPrimitive.content)
        assertEquals("object", generation["responseJsonSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(0.25f, generation["presencePenalty"]!!.jsonPrimitive.float)
        assertEquals(-0.5f, generation["frequencyPenalty"]!!.jsonPrimitive.float)
        assertFalse(
            generation["thinkingConfig"]!!.jsonObject["includeThoughts"]!!.jsonPrimitive
                .content.toBoolean()
        )

        val thresholds = body["safetySettings"]!!.jsonArray.associate { setting ->
            val value = setting.jsonObject
            value["category"]!!.jsonPrimitive.content to value["threshold"]!!.jsonPrimitive.content
        }
        assertEquals(4, thresholds.size)
        assertEquals("BLOCK_ONLY_HIGH", thresholds["HARM_CATEGORY_HARASSMENT"])
        assertEquals("BLOCK_MEDIUM_AND_ABOVE", thresholds["HARM_CATEGORY_HATE_SPEECH"])
        assertEquals("BLOCK_LOW_AND_ABOVE", thresholds["HARM_CATEGORY_SEXUALLY_EXPLICIT"])
        assertEquals("BLOCK_NONE", thresholds["HARM_CATEGORY_DANGEROUS_CONTENT"])
    }

    @Test
    fun `does not send Gemini 3 options to older Gemini models`() {
        val body = buildRequest(
            modelId = "gemini-2.5-flash",
            options = GeminiGenerationOptions(
                mediaResolution = GeminiMediaResolution.HIGH,
                seed = 42,
                stopSequences = listOf("END"),
                responseMimeType = GeminiResponseMimeType.JSON,
                responseJsonSchema = """{"type":"object"}""",
                presencePenalty = 0.25f,
                frequencyPenalty = 0.5f,
            ),
        )

        val generation = body["generationConfig"]!!.jsonObject
        assertTrue(generation.containsKey("temperature"))
        assertTrue(generation.containsKey("topP"))
        assertFalse(generation.containsKey("mediaResolution"))
        assertFalse(generation.containsKey("seed"))
        assertFalse(generation.containsKey("stopSequences"))
        assertFalse(generation.containsKey("responseMimeType"))
        assertFalse(generation.containsKey("responseJsonSchema"))
        assertFalse(generation.containsKey("presencePenalty"))
        assertFalse(generation.containsKey("frequencyPenalty"))
        assertEquals(5, body["safetySettings"]!!.jsonArray.size)
    }

    @Test
    fun `maps ultra high media resolution per media part`() {
        val body = buildRequest(
            modelId = "gemini-3.1-pro-preview",
            options = GeminiGenerationOptions(
                mediaResolution = GeminiMediaResolution.ULTRA_HIGH,
                safetySettings = GeminiSafetySettings(
                    harassment = GeminiSafetyThreshold.DEFAULT,
                ),
            ),
            messages = listOf(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Image("data:image/png;base64,AQID")),
                )
            ),
        )

        val generation = body["generationConfig"]!!.jsonObject
        assertFalse(generation.containsKey("mediaResolution"))
        val mediaPart = body["contents"]!!.jsonArray.single().jsonObject["parts"]!!
            .jsonArray.single().jsonObject
        assertEquals(
            "MEDIA_RESOLUTION_ULTRA_HIGH",
            mediaPart["mediaResolution"]!!.jsonObject["level"]!!.jsonPrimitive.content,
        )
        val safetyCategories = body["safetySettings"]!!.jsonArray.map {
            it.jsonObject["category"]!!.jsonPrimitive.content
        }
        assertFalse("HARM_CATEGORY_HARASSMENT" in safetyCategories)
        assertEquals(3, safetyCategories.size)
    }

    @Test
    fun `maps unsupported disabled reasoning to auto for Gemini 3 7 Flash`() {
        val body = buildRequest(
            modelId = "gemini-3.7-flash",
            options = GeminiGenerationOptions(),
            reasoningLevel = ReasoningLevel.OFF,
        )

        val thinking = body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
        assertNull(thinking["thinkingLevel"])
    }

    @Test
    fun `maps unsupported disabled reasoning to auto for Gemini 3 Pro`() {
        val body = buildRequest(
            modelId = "gemini-3.1-pro-preview",
            options = GeminiGenerationOptions(),
            reasoningLevel = ReasoningLevel.OFF,
        )

        val thinking = body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
        assertNull(thinking["thinkingLevel"])
    }

    private fun buildRequest(
        modelId: String,
        options: GeminiGenerationOptions,
        messages: List<UIMessage> = listOf(UIMessage.user("hello")),
        reasoningLevel: ReasoningLevel = ReasoningLevel.LOW,
    ): JsonObject {
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "buildCompletionRequestBody",
            List::class.java,
            TextGenerationParams::class.java,
        ).apply { isAccessible = true }
        return method.invoke(
            provider,
            messages,
            TextGenerationParams(
                model = Model(modelId = modelId),
                temperature = 0.7f,
                topP = 0.8f,
                reasoningLevel = reasoningLevel,
                geminiOptions = options,
            ),
        ) as JsonObject
    }
}
