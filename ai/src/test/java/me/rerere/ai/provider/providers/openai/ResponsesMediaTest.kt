package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import java.util.Base64

class ResponsesMediaTest {
    @get:Rule val temp = TemporaryFolder()
    private val provider = ProviderSetting.OpenAI(baseUrl = "https://openrouter.ai/api/v1", useResponseApi = true)
    private val params = TextGenerationParams(Model(modelId = "google/gemini-4-hydrogen"))
    private val api = ResponseAPI(OkHttpClient())

    private fun message(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.USER, parts = parts.toList())
    private fun body(vararg parts: UIMessagePart): JsonArray = api.buildRequestBody(provider,
        listOf(message(*parts)), params, false)["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray

    @Test fun `routing distinguishes model protocol endpoint and explicit gateway opt in`() {
        for (id in listOf("gemini-1.5-pro", "gemini-2.0-flash", "gemini-2.5-flash", "gemini-3-pro-preview",
            "gemini-4-argon", "gemini-4-carbon", "gemini-4-hydrogen")) {
            assertTrue(id, resolveMediaInputSupport(Model(modelId = id), provider).audio)
        }
        assertTrue(resolveMediaInputSupport(Model(modelId = "relay-alias", displayName = "Gemini 4 Carbon"), provider).video)
        assertFalse(resolveMediaInputSupport(params.model, provider.copy(useResponseApi = false)).audio)
        assertFalse(resolveMediaInputSupport(Model(modelId = "gpt-6.2"), provider).video)
        assertFalse(resolveMediaInputSupport(Model(modelId = "gemini-2.5-flash-preview-tts"), provider).audio)
        assertFalse(resolveMediaInputSupport(params.model.copy(type = ModelType.IMAGE), provider).audio)
        val unknown = provider.copy(baseUrl = "https://relay.example/v1")
        assertFalse(resolveMediaInputSupport(params.model, unknown).audio)
        assertTrue(resolveMediaInputSupport(params.model, unknown.copy(responsesMediaMode = ResponsesMediaMode.GEMINI_EXTENSIONS)).audio)
        assertFalse(resolveMediaInputSupport(params.model, provider.copy(responsesMediaMode = ResponsesMediaMode.DISABLED)).audio)
        assertFalse(resolveMediaInputSupport(params.model, provider.copy(baseUrl = "https://openrouter.ai.example/v1")).audio)
        for (host in listOf("api.openai.com", "generativelanguage.googleapis.com", "us-central1-aiplatform.googleapis.com", "api.deepseek.com")) {
            assertFalse(host, resolveMediaInputSupport(params.model,
                provider.copy(baseUrl = "https://$host/v1", responsesMediaMode = ResponsesMediaMode.GEMINI_EXTENSIONS)).audio)
        }
    }

    @Test fun `model override and native Google retain their own routing`() {
        assertTrue(resolveMediaInputSupport(params.model, ProviderSetting.Google()).audio)
        assertFalse(resolveMediaInputSupport(params.model.copy(providerOverwrite = provider.copy(useResponseApi = false)), provider).video)
        assertTrue(resolveMediaInputSupport(params.model.copy(providerOverwrite = provider), ProviderSetting.Claude()).audio)
    }

    @Test fun `old settings default safely and media mode survives serialization`() {
        val old = json.decodeFromString<ProviderSetting.OpenAI>("""{"baseUrl":"https://relay.example/v1","useResponseApi":true}""")
        assertEquals(ResponsesMediaMode.AUTO, old.responsesMediaMode)
        val configured = old.copy(responsesMediaMode = ResponsesMediaMode.GEMINI_EXTENSIONS)
        assertEquals(configured, json.decodeFromString<ProviderSetting.OpenAI>(json.encodeToString(configured)))
    }

    @Test fun `text plus audio and video keeps every part in its original order`() {
        val parts = body(UIMessagePart.Text("Describe"), UIMessagePart.Audio("data:audio/wav;base64,YWJj"),
            UIMessagePart.Video("data:video/mp4;base64,AAEC"), UIMessagePart.Text("then compare"))
        assertEquals(listOf("input_text", "input_audio", "input_video", "input_text"),
            parts.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("YWJj", parts[1].jsonObject["input_audio"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals("wav", parts[1].jsonObject["input_audio"]!!.jsonObject["format"]!!.jsonPrimitive.content)
        assertEquals("data:video/mp4;base64,AAEC", parts[2].jsonObject["video_url"]!!.jsonPrimitive.content)
        assertEquals(1, body(UIMessagePart.Audio("data:audio/wav;base64,YWJj")).size)
        assertEquals(1, body(UIMessagePart.Video("data:video/webm;base64,YWJj")).size)
    }

    @Test fun `local file bytes are preserved without audio or video preprocessing`() {
        val bytes = ByteArray(100_003) { (it % 251).toByte() }
        val audio = temp.newFile("source.wav").apply { writeBytes(bytes) }
        val video = temp.newFile("source.mov").apply { writeBytes(bytes.reversedArray()) }
        val parts = body(UIMessagePart.Audio(audio.toURI().toString()), UIMessagePart.Video(video.toURI().toString()))
        assertArrayEquals(bytes, Base64.getDecoder().decode(parts[0].jsonObject["input_audio"]!!.jsonObject["data"]!!.jsonPrimitive.content))
        assertEquals("wav", parts[0].jsonObject["input_audio"]!!.jsonObject["format"]!!.jsonPrimitive.content)
        assertArrayEquals(bytes.reversedArray(), Base64.getDecoder().decode(parts[1].jsonObject["video_url"]!!.jsonPrimitive.content.substringAfter(',')))
        val generic = temp.newFile("file.bin").apply { writeBytes(bytes) }
        assertEquals("mp3", body(UIMessagePart.Audio(generic.toURI().toString(), buildJsonObject { put("mime_type", "audio/mpeg") }))
            .single().jsonObject["input_audio"]!!.jsonObject["format"]!!.jsonPrimitive.content)
    }

    @Test fun `format aliases normalize while MIDI and unsupported media fail explicitly`() {
        assertEquals("mp3", body(UIMessagePart.Audio("data:audio/x-mp3;base64,YWJj")).single()
            .jsonObject["input_audio"]!!.jsonObject["format"]!!.jsonPrimitive.content)
        for (url in listOf("data:audio/midi;base64,YWJj", "data:audio/unknown;base64,YWJj", "data:audio/wav;base64,!!!!",
            "data:audio/wav;base64,", "https://example.test/audio.wav", "file:/missing.wav")) {
            assertThrows(url, IllegalArgumentException::class.java) { body(UIMessagePart.Audio(url)) }
        }
        assertThrows(IllegalArgumentException::class.java) { body(UIMessagePart.Video("data:video/unknown;base64,YWJj")) }
    }

    @Test fun `OpenRouter Responses restricts audio even when Gemini supports more formats`() {
        val audio = UIMessagePart.Audio("data:audio/aiff;base64,YWJj")
        assertThrows(IllegalArgumentException::class.java) { body(audio) }
        val relay = provider.copy(baseUrl = "https://relay.example/v1", responsesMediaMode = ResponsesMediaMode.GEMINI_EXTENSIONS)
        val parts = api.buildRequestBody(relay, listOf(message(audio)), params, false)["input"]!!.jsonArray
            .single().jsonObject["content"]!!.jsonArray
        assertEquals("aiff", parts.single().jsonObject["input_audio"]!!.jsonObject["format"]!!.jsonPrimitive.content)
    }

    @Test fun `batch budget fails before any media file is read`() {
        val a = temp.newFile("a.mp4")
        val b = temp.newFile("b.mp4")
        RandomAccessFile(a, "rw").use { it.setLength(8L * 1024 * 1024) }
        RandomAccessFile(b, "rw").use { it.setLength(8L * 1024 * 1024) }
        var checks = 0
        val error = assertThrows(IllegalArgumentException::class.java) {
            api.buildRequestBody(provider, listOf(message(UIMessagePart.Video(a.toURI().toString()), UIMessagePart.Video(b.toURI().toString()))), params, false) { checks++ }
        }
        assertTrue(error.message!!.contains("14 MiB"))
        assertEquals(2, checks)
    }

    @Test fun `preflight rejects an empty file and invalid formats without encoding`() {
        val empty = temp.newFile("empty.mp4")
        val support = resolveMediaInputSupport(params.model, provider)
        assertThrows(IllegalArgumentException::class.java) {
            validateResponsesMediaAttachments(listOf(UIMessagePart.Video(empty.toURI().toString())), support)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateResponsesMediaAttachments(listOf(UIMessagePart.Audio("data:audio/flac;base64,YWJj")), support)
        }
        validateResponsesMediaAttachments(listOf(UIMessagePart.Audio("data:audio/wav;base64,YWJj")), support)
    }

    @Test fun `full UTF8 request limit fails before starting a provider call`() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor {
            calls++
            error("Oversized requests must not reach the network")
        }.build()
        val text = "中".repeat(7 * 1024 * 1024)
        val requestMessages = listOf(message(UIMessagePart.Text(text), UIMessagePart.Audio("data:audio/wav;base64,YWJj")))
        val failure = try {
            ResponseAPI(client).generateText(provider, requestMessages, params)
            error("Expected request budget failure")
        } catch (expected: IllegalArgumentException) { expected }
        assertTrue(failure.message!!.contains("20 MiB"))
        assertEquals(0, calls)
    }

    @Test fun `unsupported route rejects attachments rather than silently dropping them`() {
        assertThrows(IllegalArgumentException::class.java) {
            api.buildRequestBody(provider.copy(responsesMediaMode = ResponsesMediaMode.DISABLED),
                listOf(message(UIMessagePart.Text("hi"), UIMessagePart.Audio("data:audio/wav;base64,YWJj"))), params, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            api.buildRequestBody(provider, listOf(UIMessage(role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Video("data:video/mp4;base64,YWJj")))), params, false)
        }
    }

    @Test fun `encoding cooperatively cancels during file reads`() {
        val file = temp.newFile("large.wav").apply { writeBytes(ByteArray(200_000)) }
        var checks = 0
        assertThrows(CancellationException::class.java) {
            api.buildRequestBody(provider, listOf(message(UIMessagePart.Audio(file.toURI().toString()))), params, false) {
                if (++checks == 4) throw CancellationException("stop")
            }
        }
    }

    @Test fun `streaming and non streaming share identical media and authentication`() = runBlocking {
        val observed = mutableListOf<JsonObject>()
        val output = """{"id":"r","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"ok"}]}]}"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("Bearer fixture-key", chain.request().header("Authorization"))
            assertEquals("/api/v1/responses", chain.request().url.encodedPath)
            val request = json.parseToJsonElement(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()).jsonObject
            observed += request
            val streaming = request["stream"]!!.jsonPrimitive.boolean
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body((if (streaming) "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":$output}\n\n" else output)
                    .toResponseBody((if (streaming) "text/event-stream" else "application/json").toMediaType())).build()
        }.build()
        val requestMessages = listOf(message(UIMessagePart.Text("listen"), UIMessagePart.Audio("data:audio/wav;base64,YWJj"),
            UIMessagePart.Video("data:video/mp4;base64,YWJj")))
        val configured = provider.copy(apiKey = "fixture-key")
        ResponseAPI(client).generateText(configured, requestMessages, params)
        ResponseAPI(client).streamText(configured, requestMessages, params).toList()
        assertEquals(2, observed.size)
        assertEquals(observed[0]["input"], observed[1]["input"])
    }
}
