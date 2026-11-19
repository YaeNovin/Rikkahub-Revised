package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LingkeMediaImageAPITest {
    private val provider = OpenAIProvider(OkHttpClient())
    private val supplier = ProviderSetting.OpenAI(baseUrl = "https://api.lk888.ai/v1")
    private fun mediaRequest(params: ImageGenerationParams, images: List<File> = emptyList()) =
        buildLingkeImageRequest(params, images, provider.imageGenerationConstraints(supplier, params.model))

    @Test fun `unified TT Image shares every model capability with native Image 2_6`() {
        val native = provider.imageGenerationConstraints(ProviderSetting.OpenAI(), Model(modelId = "gpt-image-2.6-flare"))
        for (model in listOf(Model(modelId = "tt-image-2.6"), Model(modelId = "deployment", displayName = "GPT Image 2.6"))) {
            val settings = if (model.modelId == "deployment") supplier.copy(baseUrl = "https://proxy.test/v1") else supplier
            val adapted = provider.imageGenerationConstraints(settings, model.copy(imageGenerationPath = "/media/generate"))
            assertEquals(native.copy(maxOutputImages = 1, supportsOutputCount = false,
                supportedGptImage26Versions = linkedSetOf("flare", "sunburst")), adapted)
        }
    }

    @Test fun `version can switch both ways without changing wire model or losing native parameters`() {
        for (version in listOf("flare", "sunburst")) {
            val body = mediaRequest(ImageGenerationParams(
                Model(modelId = "tt-image-2.6", displayName = "gpt-image-2.6-sunburst"), "fixture",
                quality = "xhigh", size = "3264x2448", outputFormat = "webp", outputCompression = 72,
                background = "transparent", moderation = "low",
                gptImage26Options = GptImage26Options(version = version, complexity = "high"),
            ))
            assertEquals("tt-image-2.6", body["model"]!!.jsonPrimitive.content)
            val options = body["params"]!!.jsonObject
            assertEquals(version, options["version"]!!.jsonPrimitive.content)
            assertEquals(72, options["output_compression"]!!.jsonPrimitive.int)
            assertEquals("low", options["moderation"]!!.jsonPrimitive.content)
            assertEquals("transparent", options["background"]!!.jsonPrimitive.content)
            assertEquals("3264x2448", options["size"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `SVG GIF and edit metadata survive the media envelope`() {
        val model = Model(modelId = "tt-image-2.6")
        val svg = mediaRequest(ImageGenerationParams(model, "vector", outputFormat = "svg",
            gptImage26Options = GptImage26Options(svgModel = "author", svgReasoningEffort = "high", version = "sunburst"),
            customBody = listOf(CustomBody("params", buildJsonObject {
                put("markup", buildJsonObject { put("image_url", "https://ref.test/markup.png") })
            }))))["params"]!!.jsonObject
        assertEquals("author", svg["svg"]!!.jsonObject["model"]!!.jsonPrimitive.content)
        assertEquals("high", svg["svg"]!!.jsonObject["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertTrue(svg.containsKey("markup"))
        val gif = mediaRequest(ImageGenerationParams(model, "loop", size = "3264x2448", outputFormat = "gif",
            gptImage26Options = GptImage26Options(frameCount = 9, frameDurationMs = 200, version = "flare")))
            .getValue("params").jsonObject
        assertEquals(9, gif["frame_count"]!!.jsonPrimitive.int)
        assertEquals(200, gif["frame_duration_ms"]!!.jsonPrimitive.int)
        assertFalse(gif.containsKey("size"))
    }

    @Test fun `legacy version defaults custom version and invalid versions are handled before transport`() {
        val model = Model(modelId = "tt-image-2.6")
        fun version(params: ImageGenerationParams) = mediaRequest(params)["params"]!!.jsonObject["version"]!!.jsonPrimitive.content
        assertEquals("flare", version(ImageGenerationParams(model, "fixture")))
        assertEquals("sunburst", version(ImageGenerationParams(model.copy(displayName = "gpt-image-2.6-sunburst"), "fixture")))
        assertEquals("sunburst", version(ImageGenerationParams(model, "fixture", customBody = listOf(CustomBody("version", JsonPrimitive("sunburst"))))))
        assertTrue(runCatching { mediaRequest(ImageGenerationParams(model, "fixture", gptImage26Options = GptImage26Options(version = "invalid"))) }.isFailure)
        val nativeModel = Model(modelId = "gpt-image-2.6-flare")
        val native = buildOpenAIImageGenerationRequestBody(ImageGenerationParams(nativeModel, "fixture", gptImage26Options = GptImage26Options(version = "sunburst")), provider.imageGenerationConstraints(ProviderSetting.OpenAI(), nativeModel))
        assertFalse(native.containsKey("version"))
    }
    @Test fun `OpenAI provider media flow emits downloaded image without sending key to result host`() = runBlocking {
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); paths.add(request.url.encodedPath)
            val body = when (request.url.encodedPath) {
                "/v1/media/generate" -> {
                    val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
                    assertEquals(setOf("model", "prompt", "params"), Json.parseToJsonElement(buffer.readUtf8()).jsonObject.keys)
                    """{"code":200,"data":{"task_id":1}}"""
                }
                "/v1/skills/task-status" -> """{"is_final":true,"state":"success","result_urls":["https://cdn.test/picture.png"],"error":null}"""
                else -> { assertNull(request.header("Authorization")); "image bytes" }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        val items = OpenAIProvider(client).generateImage(
            ProviderSetting.OpenAI(baseUrl = "https://api.lk888.ai/v1", apiKey = "test"),
            ImageGenerationParams(Model(modelId = "tt-image-2.6", type = ModelType.IMAGE), "fixture"),
        ).toList()
        try {
            assertEquals(listOf("/v1/media/generate", "/v1/skills/task-status", "/picture.png"), paths)
            assertEquals("image bytes", File(items.single().temporaryFilePath!!).readText())
        } finally { items.forEach { it.temporaryFilePath?.let(::File)?.delete() } }
    }

    @Test fun `media edit uses selected version compression and moderation with inline references`() = runBlocking {
        val image = File.createTempFile("media-edit", ".png")
        image.writeBytes(byteArrayOf(1, 2, 3))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = when (request.url.encodedPath) {
                "/v1/media/generate" -> {
                    val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
                    val options = Json.parseToJsonElement(buffer.readUtf8()).jsonObject["params"]!!.jsonObject
                    assertEquals("sunburst", options["version"]!!.jsonPrimitive.content)
                    assertEquals(65, options["output_compression"]!!.jsonPrimitive.int)
                    assertEquals("low", options["moderation"]!!.jsonPrimitive.content)
                    assertEquals("data:image/png;base64,AQID", options["images"]!!.jsonArray.single().jsonPrimitive.content)
                    """{"code":200,"data":{"task_id":1}}"""
                }
                "/v1/skills/task-status" -> """{"is_final":true,"state":"success","result_url":"https://cdn.test/result.webp"}"""
                else -> "image bytes"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        try {
            val items = OpenAIProvider(client).editImage(supplier.copy(apiKey = "test"), ImageEditParams(
                Model(modelId = "tt-image-2.6"), "fixture", listOf(image.path), outputFormat = "webp",
                outputCompression = 65, moderation = "low", gptImage26Options = GptImage26Options(version = "sunburst"),
            )).toList()
            items.forEach { it.temporaryFilePath?.let(::File)?.delete() }
        } finally { image.delete() }
    }

    @Test fun `supplier routing preserves OpenAI paths and explicit overrides`() {
        val model = Model(modelId = "tt-image-2.6")
        assertEquals(ModelType.IMAGE, model.withDetectedCapabilities().type)
        val supplier = ProviderSetting.OpenAI(baseUrl = "https://api.lk888.ai/v1")
        assertEquals("/media/generate", supplier.imageApiPath(model))
        assertEquals("/media/generate", supplier.imageApiPath(model, edit = true))
        assertEquals("/images/generations", ProviderSetting.OpenAI().imageApiPath(model))
        assertEquals("/images/edits", ProviderSetting.OpenAI().imageApiPath(model, edit = true))
        assertEquals("/images/generations", supplier.imageApiPath(model.copy(imageGenerationPath = "/images/generations")))
        assertEquals("/images/edits", supplier.imageApiPath(model.copy(imageEditPath = "/images/edits"), edit = true))
        assertEquals("https://api.lk888.ai/v1/media/generate", imageApiUrl(supplier.baseUrl, "/v1/media/generate"))
        assertEquals("https://proxy.test/api/v1/custom/images", imageApiUrl("https://proxy.test/api/v1/", "/custom/images"))
        for (path in listOf("https://other.test/images", "//other.test/images", "/../images", "/%2e/images", "/x?key=abc")) {
            assertTrue(path, runCatching { imageApiUrl(supplier.baseUrl, path) }.isFailure)
        }
    }

    @Test fun `media body nests documented options sends reference array and omits incompatible fields`() {
        val image = File.createTempFile("media-ref", ".png")
        try {
            image.writeBytes(byteArrayOf(1, 2, 3))
            val body = mediaRequest(ImageGenerationParams(
                Model(modelId = "tt-image-2.6", displayName = "gpt-image-2.6-sunburst"), "prompt",
                size = "3264x2448", quality = "xhigh", outputFormat = "png", moderation = "low",
                gptImage26Options = GptImage26Options(complexity = "high"),
                customBody = listOf(CustomBody("n", JsonPrimitive(10)), CustomBody("model", JsonPrimitive("wrong"))),
            ), listOf(image))
            assertEquals(setOf("model", "prompt", "params"), body.keys)
            assertEquals("tt-image-2.6", body["model"]!!.jsonPrimitive.content)
            val options = body["params"]!!.jsonObject
            assertEquals("sunburst", options["version"]!!.jsonPrimitive.content)
            assertEquals("xhigh", options["quality"]!!.jsonPrimitive.content)
            assertEquals("high", options["complexity"]!!.jsonPrimitive.content)
            assertEquals("data:image/png;base64,AQID", options["images"]!!.jsonArray.single().jsonPrimitive.content)
            assertEquals("low", options["moderation"]!!.jsonPrimitive.content)
            assertFalse(options.containsKey("n"))
        } finally { image.delete() }
    }

    @Test fun `media task is created once and polls until final using same origin and encoded task id`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests.add(request)
            val body = when (requests.size) {
                1 -> """{"code":200,"data":{"task_id":"task /1"}}"""
                2 -> """{"state":"running","is_final":false,"result_url":"https://cdn.test/partial.png"}"""
                else -> """{"state":"success","is_final":true,"result_urls":["https://cdn.test/result.png"],"error":null}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        val request = Request.Builder().url("https://api.lk888.ai/v1/media/generate")
            .header("Authorization", "Bearer test").post("{}".toRequestBody()).build()
        assertEquals(listOf("https://cdn.test/result.png"), LingkeMediaImageAPI(client, 0).generate(request, "https://api.lk888.ai/v1"))
        assertEquals(listOf("POST", "GET", "GET"), requests.map { it.method })
        assertTrue(requests.drop(1).all { it.url.encodedPath == "/v1/skills/task-status" && it.url.queryParameter("task_id") == "task /1" && it.header("Authorization") == "Bearer test" })
    }

    @Test fun `failed terminal task and creation error never resubmit or poll forever`() = runBlocking {
        for (creationFails in listOf(true, false)) {
            var calls = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                val body = if (creationFails) """{"code":402,"msg":"balance"}"""
                    else if (calls == 1) """{"code":200,"data":{"task_id":123}}"""
                    else """{"is_final":true,"state":"failed","error":"invalid request"}"""
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
            }.build()
            assertTrue(runCatching { LingkeMediaImageAPI(client, 0).generate(Request.Builder().url("https://proxy.test/v1/media/generate").post("{}".toRequestBody()).build(), "https://proxy.test/v1") }.isFailure)
            assertEquals(if (creationFails) 1 else 2, calls)
        }
    }

    @Test fun `poll cancellation propagates without another paid request`() = runBlocking {
        var posts = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().method == "POST") posts++
            val body = if (chain.request().method == "POST") """{"code":200,"data":{"task_id":1}}"""
                else """{"is_final":false,"state":"running"}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        val result = runCatching { withTimeout(1000) { LingkeMediaImageAPI(client).generate(Request.Builder().url("https://proxy.test/v1/media/generate").post("{}".toRequestBody()).build(), "https://proxy.test/v1") } }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(1, posts)
    }
}
