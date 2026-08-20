package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OpenAIImageRequestTest {
    private val provider = OpenAIProvider(OkHttpClient())

    @Test
    fun `xAI generation uses documented batch aspect ratio resolution and format fields`() {
        val setting = ProviderSetting.OpenAI(baseUrl = "https://api.x.ai/v1")
        val model = Model(modelId = "grok-imagine-image-2.0")
        val constraints = provider.imageGenerationConstraints(setting, model)

        val body = buildOpenAIImageGenerationRequestBody(
            params = ImageGenerationParams(
                model = model,
                prompt = "safe prompt",
                numOfImages = 12,
                size = "16:9",
                quality = "medium",
                outputFormat = "b64_json",
                resolution = "2k",
                customBody = listOf(
                    CustomBody("n", JsonPrimitive(99)),
                    CustomBody("size", JsonPrimitive("4096x4096")),
                    CustomBody("prompt", JsonPrimitive("overridden prompt")),
                ),
            ),
            constraints = constraints,
        )

        assertEquals("10", body["n"]?.toString())
        assertNull(body["size"])
        assertEquals("16:9", (body["aspect_ratio"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("2k", (body["resolution"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("medium", (body["quality"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("b64_json", (body["response_format"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("safe prompt", (body["prompt"] as? JsonPrimitive)?.contentOrNull)
        assertTrue(constraints.usesJsonImageEdit)
    }

    @Test
    fun `xAI edit uses JSON data URI instead of multipart`() {
        val setting = ProviderSetting.OpenAI(baseUrl = "https://api.x.ai/v1")
        val model = Model(modelId = "grok-imagine-image-2.0")
        val constraints = provider.imageGenerationConstraints(setting, model)
        val imageFile = File.createTempFile("xai-edit", ".png")
        try {
            imageFile.writeBytes(byteArrayOf(1, 2, 3, 4))
            val requestBody = buildXaiImageEditRequestBody(
                params = ImageEditParams(
                    model = model,
                    prompt = "edit safely",
                    images = listOf(imageFile.absolutePath),
                    size = "1:1",
                    outputFormat = "url",
                ),
                constraints = constraints,
                images = listOf(imageFile to "image/png"),
            )
            val buffer = Buffer()
            requestBody.writeTo(buffer)
            val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject

            assertEquals("edit safely", (body["prompt"] as? JsonPrimitive)?.contentOrNull)
            assertEquals("1:1", (body["aspect_ratio"] as? JsonPrimitive)?.contentOrNull)
            assertEquals("url", (body["response_format"] as? JsonPrimitive)?.contentOrNull)
            val image = body["image"]?.jsonObject
            assertEquals("image_url", (image?.get("type") as? JsonPrimitive)?.contentOrNull)
            assertEquals(
                "data:image/png;base64,AQIDBA==",
                (image?.get("url") as? JsonPrimitive)?.contentOrNull,
            )
        } finally {
            imageFile.delete()
        }
    }

    @Test
    fun `xAI edit streams up to three source images in the documented images array`() {
        val setting = ProviderSetting.OpenAI(baseUrl = "https://api.x.ai/v1")
        val model = Model(modelId = "grok-imagine-image-quality")
        val constraints = provider.imageGenerationConstraints(setting, model)
        val imageFiles = List(3) { index ->
            File.createTempFile("xai-edit-$index", ".webp").apply {
                writeBytes(byteArrayOf(index.toByte(), (index + 1).toByte()))
            }
        }
        try {
            val requestBody = buildXaiImageEditRequestBody(
                params = ImageEditParams(
                    model = model,
                    prompt = "combine references",
                    images = imageFiles.map { it.absolutePath },
                    numOfImages = 11,
                    resolution = "2k",
                    customBody = listOf(
                        CustomBody("images", JsonPrimitive("must not override source images")),
                    ),
                ),
                constraints = constraints,
                images = imageFiles.map { it to "image/webp" },
            )
            val buffer = Buffer()
            requestBody.writeTo(buffer)
            val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject

            assertNull(body["image"])
            assertEquals("10", body["n"]?.toString())
            assertEquals("2k", (body["resolution"] as? JsonPrimitive)?.contentOrNull)
            val images = body["images"]?.jsonArray
            assertEquals(3, images?.size)
            images.orEmpty().forEach { image ->
                val imageObject = image.jsonObject
                assertEquals("image_url", imageObject["type"]?.jsonPrimitive?.contentOrNull)
                assertTrue(
                    imageObject["url"]?.jsonPrimitive?.contentOrNull
                        ?.startsWith("data:image/webp;base64,") == true
                )
                assertTrue(imageObject["url"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true)
            }
        } finally {
            imageFiles.forEach(File::delete)
        }
    }

    @Test
    fun `xAI quality model aliases expose resolution but not the 2 point 0 quality selector`() {
        val setting = ProviderSetting.OpenAI(baseUrl = "https://api.x.ai/v1")

        listOf(
            "grok-imagine-image-quality",
            "grok-imagine-image-quality-latest",
            "grok-imagine-image-quality-20260403",
            "grok-imagine-image-pro",
        ).forEach { modelId ->
            val model = Model(modelId = modelId)
            val constraints = provider.imageGenerationConstraints(setting, model)
            val body = buildOpenAIImageGenerationRequestBody(
                params = ImageGenerationParams(
                    model = model,
                    prompt = "prompt",
                    numOfImages = 12,
                    quality = "medium",
                    resolution = "2k",
                ),
                constraints = constraints,
            )

            assertEquals("model=$modelId", 10, constraints.maxOutputImages)
            assertEquals("model=$modelId", 3, constraints.maxReferenceImages)
            assertTrue("model=$modelId", constraints.supportedQualityValues.isEmpty())
            assertEquals("model=$modelId", setOf("1k", "2k"), constraints.supportedResolutionValues)
            assertEquals("model=$modelId", "10", body["n"]?.toString())
            assertNull("model=$modelId", body["quality"])
            assertEquals("model=$modelId", "2k", body["resolution"]?.jsonPrimitive?.contentOrNull)
        }
    }

    @Test
    fun `xAI image 2 drops invalid documented option values`() {
        val model = Model(modelId = "grok-imagine-image-2.0")
        val constraints = provider.imageGenerationConstraints(
            ProviderSetting.OpenAI(baseUrl = "https://api.x.ai/v1"),
            model,
        )
        val body = buildOpenAIImageGenerationRequestBody(
            params = ImageGenerationParams(
                model = model,
                prompt = "prompt",
                size = "21:9",
                quality = "high",
                resolution = "4k",
            ),
            constraints = constraints,
        )

        assertNull(body["aspect_ratio"])
        assertNull(body["quality"])
        assertNull(body["resolution"])
    }

    @Test
    fun `DALL-E 3 disables editing and drops unsupported sizes`() {
        val setting = ProviderSetting.OpenAI()
        val model = Model(modelId = "dall-e-3")
        val constraints = provider.imageGenerationConstraints(setting, model)

        val body = buildOpenAIImageGenerationRequestBody(
            params = ImageGenerationParams(
                model = model,
                prompt = "prompt",
                numOfImages = 3,
                size = "1536x1024",
            ),
            constraints = constraints,
        )

        assertFalse(constraints.supportsEdit)
        assertEquals(0, constraints.maxReferenceImages)
        assertEquals("1", body["n"]?.toString())
        assertNull(body["size"])
    }

    @Test
    fun `GPT Image 1 keeps its fixed supported sizes`() {
        val setting = ProviderSetting.OpenAI()
        val model = Model(modelId = "gpt-image-1")
        val constraints = provider.imageGenerationConstraints(setting, model)

        val body = buildOpenAIImageGenerationRequestBody(
            params = ImageGenerationParams(
                model = model,
                prompt = "prompt",
                numOfImages = 4,
                size = "1536x1024",
            ),
            constraints = constraints,
        )

        assertTrue(constraints.supportsEdit)
        assertEquals(16, constraints.maxReferenceImages)
        assertEquals("4", body["n"]?.toString())
        assertEquals("1536x1024", (body["size"] as? JsonPrimitive)?.contentOrNull)
    }

    @Test
    fun `GPT Image 2 accepts documented size and output options`() {
        val setting = ProviderSetting.OpenAI()
        val model = Model(modelId = "gpt-image-2")
        val constraints = provider.imageGenerationConstraints(setting, model)

        val body = buildOpenAIImageGenerationRequestBody(
            params = ImageGenerationParams(
                model = model,
                prompt = "prompt",
                size = "2048x1152",
                numOfImages = 10,
                quality = "auto",
                outputFormat = "jpeg",
                background = "opaque",
                outputCompression = 62,
                customBody = listOf(
                    CustomBody("thinking", JsonPrimitive("high")),
                    CustomBody("response_format", JsonPrimitive("url")),
                ),
            ),
            constraints = constraints,
        )

        assertTrue(constraints.supportsCustomSize)
        assertEquals(16, constraints.customSizeMultiple)
        assertEquals(3_839, constraints.customSizeMaxDimension)
        assertTrue(
            constraints.supportedSizes.orEmpty().containsAll(
                setOf("2048x2048", "2560x1440", "1440x2560", "3824x2144", "2144x3824")
            )
        )
        assertEquals("2048x1152", (body["size"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("10", body["n"]?.toString())
        assertEquals("auto", (body["quality"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("jpeg", (body["output_format"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("opaque", (body["background"] as? JsonPrimitive)?.contentOrNull)
        val compression = body["output_compression"] as JsonPrimitive
        assertEquals(62, compression.int)
        assertFalse(compression.isString)
        assertNull(body["thinking"])
        assertNull(body["response_format"])
    }

    @Test
    fun `GPT Image 2 accepts QHD and rounded experimental UHD presets`() {
        val constraints = provider.imageGenerationConstraints(
            ProviderSetting.OpenAI(),
            Model(modelId = "gpt-image-2"),
        )

        listOf("2560x1440", "1440x2560", "3824x2144", "2144x3824").forEach { size ->
            val body = buildOpenAIImageGenerationRequestBody(
                params = ImageGenerationParams(
                    model = Model(modelId = "gpt-image-2"),
                    prompt = "prompt",
                    size = size,
                ),
                constraints = constraints,
            )
            assertEquals(size, (body["size"] as? JsonPrimitive)?.contentOrNull)
        }
    }

    @Test
    fun `GPT Image 2 enforces pixel ratio and option constraints`() {
        val setting = ProviderSetting.OpenAI()
        val model = Model(modelId = "gpt-image-2")
        val constraints = provider.imageGenerationConstraints(setting, model)

        listOf(
            "2048x1150", // not divisible by 16
            "3840x2160", // the documented edge limit is exclusive
            "3840x3840", // too many pixels
            "3200x800", // aspect ratio above 3:1
            "512x512", // too few pixels
        ).forEach { invalidSize ->
            val body = buildOpenAIImageGenerationRequestBody(
                params = ImageGenerationParams(
                    model = model,
                    prompt = "prompt",
                    size = invalidSize,
                    outputFormat = "png",
                    background = "transparent",
                    outputCompression = 50,
                    customBody = listOf(CustomBody("quality", JsonPrimitive("ultra"))),
                ),
                constraints = constraints,
            )

            assertNull("size=$invalidSize", body["size"])
            assertNull(body["quality"])
            assertNull(body["background"])
            assertNull(body["output_compression"])
        }
    }
}
