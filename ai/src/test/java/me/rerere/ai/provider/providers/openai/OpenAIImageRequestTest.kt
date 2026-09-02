package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderRequestException
import me.rerere.ai.provider.ProviderSetting
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
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
    fun `image request failure preserves provider response detail and retry metadata`() {
        val response = Response.Builder()
            .request(Request.Builder().url("https://api.openai.com/v1/images/edits").build())
            .protocol(Protocol.HTTP_1_1)
            .code(400)
            .message("Bad Request")
            .header("Retry-After", "2")
            .body(
                """{"error":{"message":"Source image format is not supported"}}"""
                    .toResponseBody("application/json".toMediaType())
            )
            .build()

        val error = openAIImageRequestFailure("edit image", response) as ProviderRequestException

        assertEquals(400, error.statusCode)
        assertEquals(2_000L, error.retryAfterMillis)
        assertTrue(error.message.orEmpty().contains("Source image format is not supported"))
    }

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
    fun `Seedream generation uses documented size and extension fields without n`() {
        val setting = ProviderSetting.OpenAI(baseUrl = "https://ark.cn-beijing.volces.com/api/v3")
        val model = Model(modelId = "doubao-seedream-5-0-lite")
        val constraints = provider.imageGenerationConstraints(setting, model)
        val body = buildOpenAIImageGenerationRequestBody(
            params = ImageGenerationParams(
                model = model,
                prompt = "prompt",
                numOfImages = 4,
                size = "4096x2304",
                quality = "high",
                outputFormat = "url",
                customBody = listOf(
                    CustomBody("n", JsonPrimitive(9)),
                    CustomBody("watermark", JsonPrimitive(false)),
                    CustomBody("sequential_image_generation", JsonPrimitive("auto")),
                    CustomBody("output_format", JsonPrimitive("png")),
                ),
            ),
            constraints = constraints,
        )

        assertTrue(constraints.supportsGeneration)
        assertTrue(constraints.supportsEdit)
        assertEquals(10, constraints.maxReferenceImages)
        assertFalse(constraints.supportsOutputCount)
        assertTrue(constraints.usesGenerationEndpointForEdit)
        assertNull(body["n"])
        assertEquals("4096x2304", body["size"]?.jsonPrimitive?.contentOrNull)
        assertEquals("url", body["response_format"]?.jsonPrimitive?.contentOrNull)
        assertEquals("false", body["watermark"]?.jsonPrimitive?.content)
        assertEquals("auto", body["sequential_image_generation"]?.jsonPrimitive?.contentOrNull)
        assertNull(body["quality"])
        assertNull(body["output_format"])

        val oversized = buildOpenAIImageGenerationRequestBody(
            params = ImageGenerationParams(
                model = model,
                prompt = "prompt",
                size = "4097x2048",
            ),
            constraints = constraints,
        )
        assertNull(oversized["size"])
    }

    @Test
    fun `Seedream 4 and 5 stream reference images through generations JSON`() {
        val setting = ProviderSetting.OpenAI(baseUrl = "https://ark.cn-beijing.volces.com/api/v3")
        listOf("doubao-seedream-4-5", "doubao-seedream-5-0-lite", "vendor/seedream-v4").forEach { modelId ->
            val model = Model(modelId = modelId)
            val constraints = provider.imageGenerationConstraints(setting, model)
            val imageFiles = listOf(
                File.createTempFile("seedream-edit", ".png").apply { writeBytes(byteArrayOf(1, 2, 3)) },
                File.createTempFile("seedream-edit", ".jpg").apply { writeBytes(byteArrayOf(4, 5, 6)) },
            )
            try {
                val requestBody = buildSeedreamImageEditRequestBody(
                    params = ImageEditParams(
                        model = model,
                        prompt = "combine references",
                        images = imageFiles.map(File::getAbsolutePath),
                        size = "2048x2048",
                        outputFormat = "b64_json",
                        customBody = listOf(CustomBody("watermark", JsonPrimitive(true))),
                    ),
                    constraints = constraints,
                    images = listOf(imageFiles[0] to "image/png", imageFiles[1] to "image/jpeg"),
                )
                val buffer = Buffer()
                requestBody.writeTo(buffer)
                val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject

                assertTrue("model=$modelId", constraints.usesJsonImageEdit)
                assertTrue("model=$modelId", constraints.usesGenerationEndpointForEdit)
                assertNull(body["n"])
                assertEquals("b64_json", body["response_format"]?.jsonPrimitive?.contentOrNull)
                assertEquals("true", body["watermark"]?.jsonPrimitive?.content)
                val images = body["image"]?.jsonArray
                assertEquals(2, images?.size)
                assertEquals("data:image/png;base64,AQID", images?.get(0)?.jsonPrimitive?.contentOrNull)
                assertEquals("data:image/jpeg;base64,BAUG", images?.get(1)?.jsonPrimitive?.contentOrNull)
            } finally {
                imageFiles.forEach(File::delete)
            }
        }
    }

    @Test
    fun `Seedream 3 remains generation only`() {
        val constraints = provider.imageGenerationConstraints(
            ProviderSetting.OpenAI(baseUrl = "https://ark.cn-beijing.volces.com/api/v3"),
            Model(modelId = "doubao-seedream-3-0-t2i"),
        )

        assertTrue(constraints.supportsGeneration)
        assertFalse(constraints.supportsEdit)
        assertEquals(0, constraints.maxReferenceImages)
        assertFalse(constraints.usesGenerationEndpointForEdit)
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
        assertEquals(3_840, constraints.customSizeMaxDimension)
        assertTrue(
            constraints.supportedSizes.orEmpty().containsAll(
                setOf("2048x2048", "2560x1440", "1440x2560", "3840x2160", "2160x3840")
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
    fun `GPT Image 2 presets cover standard ratios and satisfy official limits`() {
        val constraints = provider.imageGenerationConstraints(
            ProviderSetting.OpenAI(),
            Model(modelId = "gpt-image-2"),
        )

        assertTrue(constraints.groupSizesByAspectRatio)
        val ratios = mutableSetOf<String>()
        constraints.supportedSizes.orEmpty().filterNot { it == "auto" }.forEach { size ->
            val (width, height) = size.split('x').map(String::toInt)
            val divisor = greatestCommonDivisor(width, height)
            ratios += "${width / divisor}:${height / divisor}"
            assertEquals("width=$width", 0, width % 16)
            assertEquals("height=$height", 0, height % 16)
            assertTrue("size=$size", width <= 3_840 && height <= 3_840)
            assertTrue("size=$size", width.toLong() * height >= 655_360L)
            assertTrue("size=$size", width.toLong() * height <= 8_294_400L)
            assertTrue("size=$size", maxOf(width, height).toLong() <= minOf(width, height).toLong() * 3L)
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
        assertEquals(
            setOf(
                "1:1", "4:3", "3:4", "3:2", "2:3", "8:5", "5:8", "16:9", "9:16",
                "7:4", "4:7", "5:4", "4:5", "2:1", "1:2", "7:3", "3:7", "3:1", "1:3",
            ),
            ratios,
        )
    }

    @Test
    fun `GPT Image 2 aliases preserve arbitrary size and high quality options`() {
        val setting = ProviderSetting.OpenAI(baseUrl = "https://third-party.example/v1")

        listOf(
            "gpt-image-2",
            "gpt-image2",
            "gpt_image_2",
            "gpt image 2",
            "openai/gpt.image.2-latest",
        ).forEach { modelId ->
            val model = Model(modelId = modelId)
            val constraints = provider.imageGenerationConstraints(setting, model)
            val body = buildOpenAIImageGenerationRequestBody(
                params = ImageGenerationParams(
                    model = model,
                    prompt = "prompt",
                    size = "3840x2160",
                    quality = "high",
                ),
                constraints = constraints,
            )

            assertTrue("model=$modelId", constraints.supportsCustomSize)
            assertEquals("model=$modelId", 3_840, constraints.customSizeMaxDimension)
            assertEquals("model=$modelId", "3840x2160", body["size"]?.jsonPrimitive?.contentOrNull)
            assertEquals("model=$modelId", "high", body["quality"]?.jsonPrimitive?.contentOrNull)
        }
    }

    @Test
    fun `GPT Image 2 enforces pixel ratio and option constraints`() {
        val setting = ProviderSetting.OpenAI()
        val model = Model(modelId = "gpt-image-2")
        val constraints = provider.imageGenerationConstraints(setting, model)

        listOf(
            "2048x1150", // not divisible by 16
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

    @Test
    fun `Qwen Image sends only documented optional generation parameters`() {
        val model = Model(modelId = "qwen-image-3.0-pro")
        val constraints = provider.imageGenerationConstraints(ProviderSetting.OpenAI(), model)
        val body = buildOpenAIImageGenerationRequestBody(
            params = ImageGenerationParams(
                model = model,
                prompt = "prompt",
                seed = 42L,
                negativePrompt = "blur, extra fingers",
                promptEnhancement = false,
                watermark = true,
            ),
            constraints = constraints,
        )

        assertEquals(0L..Int.MAX_VALUE.toLong(), constraints.seedRange)
        assertEquals("42", body["seed"]?.jsonPrimitive?.contentOrNull)
        assertEquals("blur, extra fingers", body["negative_prompt"]?.jsonPrimitive?.contentOrNull)
        assertEquals("false", body["prompt_extend"]?.jsonPrimitive?.contentOrNull)
        assertEquals("true", body["watermark"]?.jsonPrimitive?.contentOrNull)
        assertNull(body["steps"])
    }

    @Test
    fun `Qwen Image 3 sends enhancement mode and thinking only when enabled`() {
        val model = Model(modelId = "qwen-image-3.0-pro")
        val constraints = provider.imageGenerationConstraints(ProviderSetting.OpenAI(), model)
        val body = buildOpenAIImageGenerationRequestBody(
            params = ImageGenerationParams(
                model = model,
                prompt = "prompt",
                promptEnhancement = true,
                promptEnhancementMode = "direct",
                imageThinking = true,
            ),
            constraints = constraints,
        )

        assertEquals(setOf("direct", "agent"), constraints.supportedPromptEnhancementModes)
        assertEquals(6, constraints.maxOutputImages)
        assertTrue(constraints.supportsImageThinking)
        assertEquals(3, constraints.maxReferenceImages)
        assertEquals("true", body["prompt_extend"]?.jsonPrimitive?.contentOrNull)
        assertEquals("direct", body["prompt_extend_mode"]?.jsonPrimitive?.contentOrNull)
        assertEquals("true", body["enable_thinking"]?.jsonPrimitive?.contentOrNull)

        val disabled = buildOpenAIImageGenerationRequestBody(
            params = ImageGenerationParams(
                model = model,
                prompt = "prompt",
                promptEnhancement = false,
                promptEnhancementMode = "agent",
                imageThinking = true,
            ),
            constraints = constraints,
        )
        assertEquals("false", disabled["prompt_extend"]?.jsonPrimitive?.contentOrNull)
        assertNull(disabled["prompt_extend_mode"])
        assertNull(disabled["enable_thinking"])
    }

    @Test
    fun `Qwen Image 3 edit suppresses unsupported agent enhancement mode`() = runBlocking {
        var capturedRequest: Request? = null
        val capturingProvider = OpenAIProvider(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    capturedRequest = chain.request()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("{\"data\":[]}".toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build()
        )
        val imageFile = File.createTempFile("qwen-edit", ".png")
        try {
            imageFile.writeBytes(byteArrayOf(1, 2, 3))
            capturingProvider.editImage(
                ProviderSetting.OpenAI(baseUrl = "https://example.com/v1"),
                ImageEditParams(
                    model = Model(modelId = "qwen-image-3.0-pro"),
                    prompt = "edit",
                    images = listOf(imageFile.absolutePath),
                    promptEnhancement = true,
                    promptEnhancementMode = "agent",
                    imageThinking = true,
                ),
            ).toList()
            val request = requireNotNull(capturedRequest)
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            val body = buffer.readUtf8()
            assertFalse(body.contains("prompt_extend_mode"))
            assertFalse(body.contains("agent"))
            assertTrue(body.contains("enable_thinking"))
        } finally {
            imageFile.delete()
        }
    }

    @Test
    fun `GPT Image moderation is emitted and input fidelity stays edit only`() {
        val model = Model(modelId = "gpt-image-1")
        val constraints = provider.imageGenerationConstraints(ProviderSetting.OpenAI(), model)
        val body = buildOpenAIImageGenerationRequestBody(
            params = ImageGenerationParams(
                model = model,
                prompt = "prompt",
                moderation = "low",
            ),
            constraints = constraints,
        )

        assertEquals(setOf("auto", "low"), constraints.supportedModerationValues)
        assertEquals(setOf("low", "high"), constraints.supportedInputFidelityValues)
        assertEquals("low", body["moderation"]?.jsonPrimitive?.contentOrNull)
        assertNull(body["input_fidelity"])
    }

    @Test
    fun `FLUX dev bounds steps guidance and seed before sending`() {
        val model = Model(modelId = "flux-dev")
        val constraints = provider.imageGenerationConstraints(ProviderSetting.OpenAI(), model)
        val valid = buildOpenAIImageGenerationRequestBody(
            params = ImageGenerationParams(
                model = model,
                prompt = "prompt",
                seed = 7L,
                steps = 28,
                guidanceScale = 3f,
                promptEnhancement = true,
            ),
            constraints = constraints,
        )
        val invalid = buildOpenAIImageGenerationRequestBody(
            params = ImageGenerationParams(
                model = model,
                prompt = "prompt",
                seed = -1L,
                steps = 51,
                guidanceScale = 5.1f,
            ),
            constraints = constraints,
        )

        assertEquals(1..50, constraints.stepsRange)
        assertEquals("7", valid["seed"]?.jsonPrimitive?.contentOrNull)
        assertEquals("28", valid["steps"]?.jsonPrimitive?.contentOrNull)
        assertEquals("3.0", valid["guidance"]?.jsonPrimitive?.contentOrNull)
        assertEquals("true", valid["prompt_upsampling"]?.jsonPrimitive?.contentOrNull)
        assertNull(invalid["seed"])
        assertNull(invalid["steps"])
        assertNull(invalid["guidance"])
    }

    @Test
    fun `FLUX dev emits the documented safety tolerance range`() {
        val model = Model(modelId = "flux-dev")
        val constraints = provider.imageGenerationConstraints(ProviderSetting.OpenAI(), model)
        val body = buildOpenAIImageGenerationRequestBody(
            params = ImageGenerationParams(
                model = model,
                prompt = "prompt",
                safetyTolerance = 6,
            ),
            constraints = constraints,
        )
        val invalid = buildOpenAIImageGenerationRequestBody(
            params = ImageGenerationParams(
                model = model,
                prompt = "prompt",
                safetyTolerance = 7,
            ),
            constraints = constraints,
        )

        assertEquals(0..6, constraints.safetyToleranceRange)
        assertEquals("6", body["safety_tolerance"]?.jsonPrimitive?.contentOrNull)
        assertNull(invalid["safety_tolerance"])
    }

    @Test
    fun `OpenAI image models ignore unsupported sampling parameters`() {
        val model = Model(modelId = "gpt-image-1")
        val constraints = provider.imageGenerationConstraints(ProviderSetting.OpenAI(), model)
        val body = buildOpenAIImageGenerationRequestBody(
            params = ImageGenerationParams(
                model = model,
                prompt = "prompt",
                seed = 42L,
                steps = 30,
                guidanceScale = 4f,
                negativePrompt = "blur",
                promptEnhancement = true,
                watermark = true,
            ),
            constraints = constraints,
        )

        assertNull(body["seed"])
        assertNull(body["steps"])
        assertNull(body["guidance"])
        assertNull(body["cfg_scale"])
        assertNull(body["negative_prompt"])
        assertNull(body["prompt_extend"])
        assertNull(body["watermark"])
    }

    @Test
    fun `Stable Diffusion uses official legacy sampling ranges`() {
        val model = Model(modelId = "stable-diffusion-xl-1024-v1-0")
        val constraints = provider.imageGenerationConstraints(ProviderSetting.OpenAI(), model)
        val body = buildOpenAIImageGenerationRequestBody(
            params = ImageGenerationParams(
                model = model,
                prompt = "prompt",
                seed = Int.MAX_VALUE.toLong(),
                steps = 150,
                guidanceScale = 35f,
                negativePrompt = "blur",
            ),
            constraints = constraints,
        )

        assertEquals(0L..Int.MAX_VALUE.toLong(), constraints.seedRange)
        assertEquals(10..150, constraints.stepsRange)
        assertEquals(30, constraints.defaultSteps)
        assertEquals(1f..35f, constraints.guidanceScaleRange)
        assertEquals(Int.MAX_VALUE.toString(), body["seed"]?.jsonPrimitive?.contentOrNull)
        assertEquals("150", body["steps"]?.jsonPrimitive?.contentOrNull)
        assertEquals("35.0", body["cfg_scale"]?.jsonPrimitive?.contentOrNull)
        assertEquals("blur", body["negative_prompt"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `Stable Diffusion emits supported sampler and style preset values`() {
        val model = Model(modelId = "stable-diffusion-xl-1024-v1-0")
        val constraints = provider.imageGenerationConstraints(ProviderSetting.OpenAI(), model)
        val body = buildOpenAIImageGenerationRequestBody(
            params = ImageGenerationParams(
                model = model,
                prompt = "prompt",
                sampler = "K_EULER",
                stylePreset = "cinematic",
            ),
            constraints = constraints,
        )
        val invalid = buildOpenAIImageGenerationRequestBody(
            params = ImageGenerationParams(
                model = model,
                prompt = "prompt",
                sampler = "unsupported",
                stylePreset = "unsupported",
            ),
            constraints = constraints,
        )

        assertTrue(constraints.supportedSamplerValues.contains("K_EULER"))
        assertTrue(constraints.supportedStylePresetValues.contains("cinematic"))
        assertEquals("K_EULER", body["sampler"]?.jsonPrimitive?.contentOrNull)
        assertEquals("cinematic", body["style_preset"]?.jsonPrimitive?.contentOrNull)
        assertNull(invalid["sampler"])
        assertNull(invalid["style_preset"])
    }

    @Test
    fun `Seedream emits sequential options and prompt optimization as nested objects`() {
        val model = Model(modelId = "doubao-seedream-5-0-lite")
        val constraints = provider.imageGenerationConstraints(ProviderSetting.OpenAI(), model)
        val body = buildOpenAIImageGenerationRequestBody(
            params = ImageGenerationParams(
                model = model,
                prompt = "prompt",
                sequentialImageGeneration = true,
                sequentialMaxImages = 99,
                promptOptimizationMode = "fast",
                watermark = false,
            ),
            constraints = constraints,
        )

        assertEquals(15, constraints.sequentialImageMax)
        assertEquals("auto", body["sequential_image_generation"]?.jsonPrimitive?.contentOrNull)
        assertEquals(15, body["sequential_image_generation_options"]?.jsonObject?.get("max_images")?.jsonPrimitive?.int)
        assertEquals("fast", body["optimize_prompt_options"]?.jsonObject?.get("mode")?.jsonPrimitive?.contentOrNull)
        assertEquals("false", body["watermark"]?.jsonPrimitive?.contentOrNull)
    }

    private tailrec fun greatestCommonDivisor(left: Int, right: Int): Int =
        if (right == 0) left else greatestCommonDivisor(right, left % right)
}
