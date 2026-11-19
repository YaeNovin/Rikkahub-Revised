package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

class GptImage26Test {
    private val provider = OpenAIProvider(OkHttpClient())
    private fun constraints(model: String) = provider.imageGenerationConstraints(ProviderSetting.OpenAI(), Model(modelId = model))

    @Test fun `both Image 2_6 models expose complexity GIF SVG and the extended quality levels`() {
        for (id in listOf("gpt-image-2.6-flare", "openai/gpt-image-2.6-sunburst")) {
            val limits = constraints(id)
            assertTrue(limits.supportsGptImage26Options)
            assertTrue(limits.supportedQualityValues.containsAll(setOf("high", "xhigh", "max")))
            assertTrue(limits.supportedOutputFormats.containsAll(setOf("png", "gif", "svg")))
            val gif = buildOpenAIImageGenerationRequestBody(ImageGenerationParams(
                Model(modelId = id), "Animated loop", size = "512x512", outputFormat = "gif",
                gptImage26Options = GptImage26Options(complexity = "medium", frameCount = 9, frameDurationMs = 200),
                customBody = listOf(CustomBody("stream", JsonPrimitive(true)), CustomBody("partial_images", JsonPrimitive(2))),
            ), limits)
            assertEquals(9, gif["frame_count"]!!.jsonPrimitive.int)
            assertEquals(200, gif["frame_duration_ms"]!!.jsonPrimitive.int)
            assertEquals("medium", gif["complexity"]!!.jsonPrimitive.content)
            assertFalse(gif.containsKey("stream")); assertFalse(gif.containsKey("partial_images"))
            assertFalse(gif.containsKey("output_compression"))
        }
    }

    @Test fun `SVG enforces one output preserves transparency and keeps author reasoning separate`() {
        val model = Model(modelId = "gpt-image-2.6-sunburst")
        val body = buildOpenAIImageGenerationRequestBody(ImageGenerationParams(
            model, "Vector mark", numOfImages = 8, outputFormat = "svg", background = "transparent",
            gptImage26Options = GptImage26Options(complexity = "low", svgModel = "svg-author", svgReasoningEffort = "high"),
        ), constraints(model.modelId))
        assertEquals(1, body["n"]!!.jsonPrimitive.int)
        assertEquals("transparent", body["background"]!!.jsonPrimitive.content)
        assertEquals("low", body["complexity"]!!.jsonPrimitive.content)
        assertEquals("high", body["svg"]!!.jsonObject["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test fun `Image 2_6 JSON edits stream input bytes and retain structured markup`() {
        val image = File.createTempFile("image26-", ".png")
        try {
            image.writeBytes(ByteArray(70000) { (it % 251).toByte() })
            val model = Model(modelId = "gpt-image-2.6-flare")
            val markup = buildJsonObject { put("image_url", "https://example.test/markup.png") }
            val request = buildGptImage26EditRequestBody(ImageEditParams(
                model, "Edit the marked region", listOf(image.path),
                gptImage26Options = GptImage26Options(complexity = "high"),
                customBody = listOf(CustomBody("markup", markup)),
            ), constraints(model.modelId), listOf(image to "image/png"))
            val buffer = Buffer(); request.writeTo(buffer)
            val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val encoded = body["images"]!!.jsonArray.single().jsonObject["image_url"]!!.jsonPrimitive.content
            assertArrayEquals(image.readBytes(), Base64.getDecoder().decode(encoded.substringAfter(",")))
            assertEquals(markup, body["markup"])
            assertFalse(body.containsKey("image"))
        } finally { image.delete() }
    }

    @Test fun `new options do not leak to earlier models and invalid GIF requests fail before sending`() {
        val old = Model(modelId = "gpt-image-2")
        val body = buildOpenAIImageGenerationRequestBody(ImageGenerationParams(
            old, "Still image", gptImage26Options = GptImage26Options(complexity = "high", frameCount = 9),
        ), constraints(old.modelId))
        assertFalse(body.containsKey("complexity")); assertFalse(body.containsKey("frame_count"))
        assertTrue(runCatching { normalizeGptImage26Request(buildJsonObject {
            put("output_format", "gif"); put("frame_count", 17)
        }) }.isFailure)
        assertTrue(runCatching { normalizeGptImage26Request(buildJsonObject {
            put("output_format", "gif"); put("mask", buildJsonObject { put("image_url", "https://example.test/mask") })
        }) }.isFailure)
        assertEquals("image/svg+xml", "svg".toImageMimeType())
        assertEquals("image/gif", "gif".toImageMimeType())
    }

    @Test fun `malformed custom animation and SVG fields fail before transport`() {
        for (body in listOf(
            """{"output_format":{}}""",
            """{"complexity":[]}""",
            """{"output_format":"gif","frame_count":{}}""",
            """{"output_format":"gif","frame_count":"9"}""",
            """{"output_format":"gif","frame_duration_ms":19}""",
            """{"output_format":"gif","size":[]}""",
            """{"output_format":"svg","svg":"author"}""",
            """{"output_format":"svg","svg":{"model":"author","reasoning":{"effort":"unknown"}}}""",
        )) assertTrue(body, runCatching { normalizeGptImage26Request(Json.parseToJsonElement(body).jsonObject) }.isFailure)
    }
}
