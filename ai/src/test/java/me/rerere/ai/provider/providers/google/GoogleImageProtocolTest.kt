package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.GeminiImageGenerationOptions
import me.rerere.ai.provider.GeminiSafetySettings
import me.rerere.ai.provider.GeminiSafetyThreshold
import me.rerere.ai.provider.ImageGenerationConstraints
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderRequestException
import me.rerere.ai.provider.ProviderRequestChannel
import me.rerere.ai.provider.ProviderRequestOperation
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.isRetryableProviderFailure
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleImageProtocolTest {
    private val constraints = ImageGenerationConstraints(
        supportsGeneration = true,
        supportsEdit = true,
        supportsPartialImages = false,
        supportedSizes = GOOGLE_EXTENDED_IMAGE_ASPECT_RATIOS,
        supportsCustomSize = false,
        supportedResolutionValues = GOOGLE_31_IMAGE_RESOLUTIONS,
        supportedThinkingValues = GOOGLE_31_IMAGE_THINKING_LEVELS,
        supportsTextResponse = true,
        supportsSafetySettings = true,
        supportsWebSearchGrounding = true,
        supportsImageSearchGrounding = true,
    )

    @Test
    fun `uses documented aspect ratios and resolution values per Gemini image family`() {
        val provider = GoogleProvider(OkHttpClient())
        val setting = ProviderSetting.Google()
        val flash31 = provider.imageGenerationConstraints(
            setting,
            Model(modelId = "gemini-3.1-flash-image"),
        )
        val pro3 = provider.imageGenerationConstraints(
            setting,
            Model(modelId = "gemini-3-pro-image"),
        )
        val flash25 = provider.imageGenerationConstraints(
            setting,
            Model(modelId = "gemini-2.5-flash-image"),
        )
        val flashLite31 = provider.imageGenerationConstraints(
            setting,
            Model(modelId = "gemini-3.1-flash-lite-image"),
        )

        assertTrue("1:8" in flash31.supportedSizes.orEmpty())
        assertEquals(linkedSetOf("512", "1K", "2K", "4K"), flash31.supportedResolutionValues)
        assertFalse("1:8" in pro3.supportedSizes.orEmpty())
        assertEquals(linkedSetOf("1K", "2K", "4K"), pro3.supportedResolutionValues)
        assertEquals(linkedSetOf("1K"), flashLite31.supportedResolutionValues)
        assertEquals(linkedSetOf("minimal", "high"), flashLite31.supportedThinkingValues)
        assertEquals(linkedSetOf("minimal", "high"), flash31.supportedThinkingValues)
        assertEquals(emptySet<String>(), pro3.supportedThinkingValues)
        assertEquals(emptySet<String>(), flash25.supportedResolutionValues)
        assertEquals("aspect_ratio", flash31.sizeRequestField)
        assertTrue(flash31.supportsWebSearchGrounding)
        assertTrue(flash31.supportsImageSearchGrounding)
        assertTrue(pro3.supportsWebSearchGrounding)
        assertFalse(pro3.supportsImageSearchGrounding)
        assertFalse(flashLite31.supportsWebSearchGrounding)
        assertFalse(flashLite31.supportsImageSearchGrounding)
    }

    @Test
    fun `builds Gemini image generation and edit request`() {
        val imagePart = Json.parseToJsonElement(
            """{"inlineData":{"mimeType":"image/png","data":"reference"}}"""
        ).jsonObject

        val body = buildGoogleImageRequestBody(
            prompt = "Draw a lighthouse",
            size = "16:9",
            resolution = "2K",
            thinkingLevel = "high",
            geminiOptions = GeminiImageGenerationOptions(
                webSearchGrounding = true,
                imageSearchGrounding = true,
                safetySettings = GeminiSafetySettings(
                    harassment = GeminiSafetyThreshold.BLOCK_ONLY_HIGH,
                    hateSpeech = GeminiSafetyThreshold.DEFAULT,
                    sexuallyExplicit = GeminiSafetyThreshold.DEFAULT,
                    dangerousContent = GeminiSafetyThreshold.DEFAULT,
                ),
            ),
            customBody = emptyList(),
            constraints = constraints,
            imageParts = listOf(imagePart),
        )

        val content = body["contents"]!!.jsonArray.single().jsonObject
        val parts = content["parts"]!!.jsonArray
        val generationConfig = body["generationConfig"]!!.jsonObject
        val imageConfig = generationConfig["imageConfig"]!!.jsonObject
        assertEquals("user", content["role"]!!.jsonPrimitive.content)
        assertEquals("Draw a lighthouse", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("reference", parts[1].jsonObject["inlineData"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals(listOf("TEXT", "IMAGE"), generationConfig["responseModalities"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("16:9", imageConfig["aspectRatio"]!!.jsonPrimitive.content)
        assertEquals("2K", imageConfig["imageSize"]!!.jsonPrimitive.content)
        assertEquals(
            "high",
            generationConfig["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content,
        )
        val searchTypes = body["tools"]!!.jsonArray.single().jsonObject["googleSearch"]!!
            .jsonObject["searchTypes"]!!.jsonObject
        assertTrue(searchTypes.containsKey("webSearch"))
        assertTrue(searchTypes.containsKey("imageSearch"))
        val safetySettings = body["safetySettings"]!!.jsonArray
        assertEquals(1, safetySettings.size)
        assertEquals(
            "BLOCK_ONLY_HIGH",
            safetySettings.single().jsonObject["threshold"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `omits unsupported options and ignores thought images`() {
        val body = buildGoogleImageRequestBody(
            prompt = "Draw a lighthouse",
            size = "1024x1024",
            resolution = "8K",
            thinkingLevel = "medium",
            customBody = emptyList(),
            constraints = constraints,
            imageParts = emptyList(),
        )
        assertFalse(body["generationConfig"]!!.jsonObject.containsKey("imageConfig"))
        assertFalse(body["generationConfig"]!!.jsonObject.containsKey("thinkingConfig"))

        val response = Json.parseToJsonElement(
            """
            {"candidates":[{"content":{"parts":[
              {"thought":true,"inlineData":{"mimeType":"image/png","data":"draft"}},
              {"inlineData":{"mimeType":"image/webp","data":"final"}},
              {"text":"Done"}
            ]}}]}
            """.trimIndent()
        ).jsonObject
        val images = parseGoogleGeneratedImages(response)

        assertEquals(1, images.size)
        assertEquals("final", images.single().data)
        assertEquals("image/webp", images.single().mimeType)
        assertTrue(images.none { it.data == "draft" })
    }

    @Test
    fun `supports image-only output and filters unsupported grounding`() {
        val body = buildGoogleImageRequestBody(
            prompt = "Draw a lighthouse",
            size = "1:1",
            resolution = "1K",
            geminiOptions = GeminiImageGenerationOptions(
                includeTextResponse = false,
                webSearchGrounding = true,
                imageSearchGrounding = true,
            ),
            customBody = emptyList(),
            constraints = constraints.copy(
                supportsWebSearchGrounding = false,
                supportsImageSearchGrounding = false,
            ),
            imageParts = emptyList(),
        )

        assertEquals(
            listOf("IMAGE"),
            body["generationConfig"]!!.jsonObject["responseModalities"]!!
                .jsonArray.map { it.jsonPrimitive.content },
        )
        assertFalse(body.containsKey("tools"))
        assertFalse(body.containsKey("safetySettings"))
    }

    @Test
    fun `diagnostics report final Gemini image fields and AI Studio channel`() {
        val setting = ProviderSetting.Google(
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/",
        )
        val body = buildGoogleImageRequestBody(
            prompt = "private prompt",
            size = "16:9",
            resolution = "4K",
            thinkingLevel = "minimal",
            customBody = emptyList(),
            constraints = constraints.copy(
                supportedThinkingValues = GOOGLE_31_IMAGE_THINKING_LEVELS,
            ),
            imageParts = emptyList(),
        )

        val diagnostics = buildGoogleRequestDiagnostics(
            providerSetting = setting,
            model = Model(modelId = "gemini-3.1-flash-image"),
            operation = ProviderRequestOperation.IMAGE_GENERATION,
            requestBody = body,
        )

        assertEquals(ProviderRequestChannel.GOOGLE_AI_STUDIO, diagnostics.channel)
        assertEquals("16:9", diagnostics.parameters["imageConfig.aspectRatio"])
        assertEquals("4K", diagnostics.parameters["imageConfig.imageSize"])
        assertEquals("minimal", diagnostics.parameters["thinkingConfig.thinkingLevel"])
        assertEquals("false", diagnostics.parameters["tools.googleSearch.webSearch"])
        assertEquals("false", diagnostics.parameters["tools.googleSearch.imageSearch"])
        assertEquals("14", diagnostics.parameters["prompt.characters"])
        assertEquals("0", diagnostics.parameters["referenceImages.encodedBytes"])
        assertEquals("0", diagnostics.parameters["referenceImages"])
        assertEquals("omitted (API default)", diagnostics.parameters["safety.harassment"])
        assertEquals("omitted (API default)", diagnostics.parameters["safety.hate_speech"])
        assertEquals("omitted (API default)", diagnostics.parameters["safety.sexually_explicit"])
        assertEquals("omitted (API default)", diagnostics.parameters["safety.dangerous_content"])
        assertEquals("none", diagnostics.parameters["customBody"])
        assertFalse(diagnostics.parameters.values.any { "private prompt" in it })

        val editDiagnostics = buildGoogleRequestDiagnostics(
            providerSetting = setting,
            model = Model(modelId = "gemini-3.1-flash-image"),
            operation = ProviderRequestOperation.IMAGE_EDIT,
            requestBody = body,
            referenceImageCount = 1,
            hasCustomBody = true,
        )
        assertEquals(ProviderRequestOperation.IMAGE_EDIT, editDiagnostics.operation)
        assertEquals("1", editDiagnostics.parameters["referenceImages"])
        assertEquals("configured", editDiagnostics.parameters["customBody"])
    }

    @Test
    fun `classifies Vertex and compatible Google endpoints`() {
        assertEquals(
            ProviderRequestChannel.VERTEX_AI,
            ProviderSetting.Google(vertexAI = true).requestChannel(),
        )
        assertEquals(
            ProviderRequestChannel.COMPATIBLE_ENDPOINT,
            ProviderSetting.Google(baseUrl = "https://google.example.com/v1beta").requestChannel(),
        )
    }

    @Test
    fun `chat request restores native image output for legacy Gemini model settings`() {
        val provider = GoogleProvider(OkHttpClient())
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "buildCompletionRequestBody",
            List::class.java,
            TextGenerationParams::class.java,
        ).apply { isAccessible = true }
        val body = method.invoke(
            provider,
            listOf(UIMessage.user("Draw a lighthouse")),
            TextGenerationParams(
                model = Model(
                    modelId = "gemini-3.1-flash-image",
                    outputModalities = listOf(Modality.TEXT),
                ),
            ),
        ) as kotlinx.serialization.json.JsonObject

        val responseModalities = body["generationConfig"]!!.jsonObject["responseModalities"]!!
            .jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("TEXT", "IMAGE"), responseModalities)
    }

    @Test
    fun `chat image generation tool enables image responses without an empty tools array`() {
        val provider = GoogleProvider(OkHttpClient())
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "buildCompletionRequestBody",
            List::class.java,
            TextGenerationParams::class.java,
        ).apply { isAccessible = true }
        val body = method.invoke(
            provider,
            listOf(UIMessage.user("Draw a lighthouse")),
            TextGenerationParams(
                model = Model(
                    modelId = "gemini-3.1-flash-image",
                    outputModalities = listOf(Modality.TEXT),
                    tools = setOf(BuiltInTools.ImageGeneration),
                ),
            ),
        ) as kotlinx.serialization.json.JsonObject

        val responseModalities = body["generationConfig"]!!.jsonObject["responseModalities"]!!
            .jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("TEXT", "IMAGE"), responseModalities)
        assertFalse(body.containsKey("tools"))
    }

    @Test
    fun `chat image stream retries when it ends after reasoning only`() {
        val decoder = GoogleStreamDecoder(
            responseId = "response",
            model = "gemini-3.1-flash-image",
            expectsImageOutput = true,
        )
        val decoded = decoder.accept(
            SseEvent(
                data = """{"candidates":[{"content":{"parts":[{"text":"draft","thought":true}]},"finishReason":"STOP"}]}""",
            )
        )

        assertTrue(decoded.chunks.any { it is StreamChunk.ReasoningDelta })
        val error = assertThrows(ProviderRequestException::class.java) {
            decoder.onClosed()
        }
        assertTrue(error.isRetryableProviderFailure())
    }

    @Test
    fun `chat image stream emits final inline image`() {
        val decoder = GoogleStreamDecoder(
            responseId = "response",
            model = "gemini-3.1-flash-image",
            expectsImageOutput = true,
        )
        val decoded = decoder.accept(
            SseEvent(
                data = """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"image/png","data":"aW1hZ2U="}}]},"finishReason":"STOP"}]}""",
            )
        )

        assertTrue(decoded.chunks.any { it is StreamChunk.ImageDelta })
        assertTrue(decoder.onClosed().any { it is StreamChunk.ImageEnd })
    }
}
