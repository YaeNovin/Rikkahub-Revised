package me.rerere.tts.provider

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.asr.ASRProviderSetting
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class SpeechModelCatalogTest {
    private fun entries(vararg ids: String) = ids.map { buildJsonObject { put("id", it) } }
    private fun source(service: SpeechModelService = SpeechModelService.MIMO, use: SpeechModelUse = SpeechModelUse.SYNTHESIS) =
        SpeechModelSource(service, use, "https://api.example.test/v1", "test-secret")

    @Test fun mimoMixedCatalogSeparatesSynthesisAndRecognition() {
        val models = entries("mimo-v2.5", "mimo-v2.5-pro", "mimo-v2.5-asr", "mimo-v2.5-tts",
            "mimo-v2.5-tts-voiceclone", "mimo-v2.5-tts-voicedesign")
        assertEquals(3, filterSpeechModels(models, source()).models.size)
        assertEquals(listOf("mimo-v2.5-asr"),
            filterSpeechModels(models, source(use = SpeechModelUse.TRANSCRIPTION)).models.map { it.id })
    }
    @Test fun openAiSynthesisExcludesChatAudioRealtimeAndImages() {
        val models = entries("gpt-6", "gpt-image-2", "gpt-audio-1.5", "gpt-realtime", "gpt-4o-transcribe",
            "gpt-4o-mini-tts", "tts-1", "tts-1-hd", "text-embedding-3-small")
        assertEquals(listOf("gpt-4o-mini-tts", "tts-1", "tts-1-hd"),
            filterSpeechModels(models, source(SpeechModelService.OPENAI)).models.map { it.id })
    }
    @Test fun realtimeExcludesFileOnlyAndDiarizationModels() {
        val models = entries("whisper-1", "gpt-transcribe", "gpt-4o-transcribe-diarize", "gpt-realtime",
            "gpt-4o-transcribe", "gpt-4o-mini-transcribe", "gpt-live-transcribe")
        assertEquals(3, filterSpeechModels(models,
            source(SpeechModelService.OPENAI, SpeechModelUse.REALTIME_TRANSCRIPTION)).models.size)
    }
    @Test fun qwenOnlyListsModelsCompatibleWithSelectedTransport() {
        val models = entries("qwen3-asr-flash", "qwen3-asr-flash-realtime", "paraformer-realtime-v2",
            "qwen3-tts-flash", "qwen3-tts-flash-realtime", "cosyvoice-v3-plus", "qwen3-omni")
        assertEquals(listOf("qwen3-tts-flash"), filterSpeechModels(models,
            source(SpeechModelService.QWEN)).models.map { it.id })
        assertEquals(listOf("qwen3-asr-flash-realtime"), filterSpeechModels(models,
            source(SpeechModelService.QWEN, SpeechModelUse.REALTIME_TRANSCRIPTION)).models.map { it.id })
    }
    @Test fun explicitCapabilitiesOverrideNamesAndSupportOpaqueIds() {
        val models = parseSpeechCatalogPage("""[
            {"model_id":"opaque-123","can_do_text_to_speech":true},
            {"model_id":"eleven_v3","can_do_text_to_speech":false},
            {"model_id":"eleven_multilingual_sts_v2","can_do_voice_conversion":true},
            {"model_id":"eleven_music_v1"},
            {"model_id":"eleven_v3","can_do_text_to_speech":true,"active":false}
        ]""").entries
        assertEquals(listOf("opaque-123"), filterSpeechModels(models,
            source(SpeechModelService.ELEVENLABS)).models.map { it.id })
    }
    @Test fun googleParsesNamesAndGenerationMethods() {
        val page = parseSpeechCatalogPage("""{"models":[
            {"name":"models/gemini-2.5-flash-preview-tts","displayName":"Voice","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-embedding-tts","supportedGenerationMethods":["embedContent"]},
            {"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent"]}
        ],"nextPageToken":"page2"}""")
        val result = filterSpeechModels(page.entries, source(SpeechModelService.GOOGLE))
        assertEquals("page2", page.cursor)
        assertEquals(listOf(SpeechCatalogModel("gemini-2.5-flash-preview-tts", "Voice")), result.models)
    }
    @Test fun deduplicationPreservesThirdPartyRoutingId() {
        val result = filterSpeechModels(entries("xiaomi/mimo-v2.5-tts", "xiaomi/mimo-v2.5-tts", "other/chat"),
            source())
        assertEquals(listOf("xiaomi/mimo-v2.5-tts"), result.models.map { it.id })
        val settings = TTSProviderSetting.MiMo(model = result.models.single().id, voice = "Mia")
        assertTrue(settings.capabilities().recognized)
        assertEquals("xiaomi/mimo-v2.5-tts", buildMiMoSpeechRequest(settings, "Hello")["model"]!!.jsonPrimitive.content)
    }
    @Test fun miniMaxSpeechFamilyIsNotConfusedWithVideoOrText() {
        val result = filterSpeechModels(entries("MiniMax-M3", "MiniMax-H3", "speech-2.8-hd", "speech-02-turbo",
            "music-2.6", "speechwriter"), source(SpeechModelService.MINIMAX))
        assertEquals(listOf("speech-02-turbo", "speech-2.8-hd"), result.models.map { it.id })
    }
    @Test fun modelListAuthenticationUsesTheCorrectHeader() {
        assertEquals("test-secret", source().modelListRequest().header("api-key"))
        assertNull(source().modelListRequest().header("Authorization"))
        assertEquals("test-secret", source(SpeechModelService.GOOGLE).modelListRequest().header("x-goog-api-key"))
        assertEquals("test-secret", source(SpeechModelService.ELEVENLABS).modelListRequest().header("xi-api-key"))
        assertEquals("Bearer test-secret", source(SpeechModelService.OPENAI).modelListRequest().header("Authorization"))
        assertFalse(source().toString().contains("test-secret"))
    }
    @Test fun websocketUrlsUseSameOriginAndRetainGatewayPrefix() {
        val openai = ASRProviderSetting.OpenAIRealtime(websocketUrl = "wss://gateway.test/proxy/v1/realtime?intent=transcription").speechModelSource()!!
        assertEquals("https://gateway.test/proxy/v1/models", openai.modelListUrl().toString())
        val qwen = ASRProviderSetting.DashScope(websocketUrl = "wss://workspace.test/api-ws/v1/realtime?model=old").speechModelSource()!!
        assertEquals("https://workspace.test/compatible-mode/v1/models", qwen.modelListUrl().toString())
        assertEquals("https://api.stepfun.com/v1/models", TTSProviderSetting.Step().speechModelSource()!!.modelListUrl().toString())
    }
    @Test fun unavailableOfficialCatalogsAreNotPresentedAsFetchedModels() {
        assertNotNull(TTSProviderSetting.MiniMax().speechModelSource()!!.unavailableReason)
        assertNull(TTSProviderSetting.MiniMax(baseUrl = "https://gateway.test/v1").speechModelSource()!!.unavailableReason)
        assertNotNull(TTSProviderSetting.FishAudio().speechModelSource()!!.unavailableReason)
        assertNull(TTSProviderSetting.SystemTTS().speechModelSource())
        assertNull(ASRProviderSetting.Volcengine().speechModelSource())
    }
    @Test(expected = IllegalArgumentException::class) fun missingKeyNeverCreatesARequest() {
        source().copy(apiKey = " ").modelListRequest()
    }
    @Test(expected = IllegalStateException::class) fun malformedCatalogIsNotTreatedAsAnEmptySuccess() {
        parseSpeechCatalogPage("""{"message":"service unavailable"}""")
    }
    @Test fun displayNamesWithoutModelIdentifiersAreIgnored() {
        val page = parseSpeechCatalogPage("""[{"name":"My Voice","can_do_text_to_speech":true}]""")
        assertTrue(filterSpeechModels(page.entries, source(SpeechModelService.ELEVENLABS)).models.isEmpty())
    }
    private fun fakeClient(respond: (Request) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (code, body) = respond(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()

    @Test fun fetchReadsAllGooglePagesAndFiltersOnlyAfterRetrieval() = runBlocking {
        val urls = mutableListOf<String>()
        val catalog = SpeechModelCatalog(fakeClient { request ->
            urls.add(request.url.toString())
            assertEquals("test-secret", request.header("x-goog-api-key"))
            200 to if (request.url.queryParameter("pageToken") == null)
                """{"models":[{"name":"models/gemini-chat"}],"nextPageToken":"opaque/token"}"""
            else """{"models":[{"name":"models/gemini-tts"}]}"""
        })
        val result = catalog.fetch(source(SpeechModelService.GOOGLE))
        assertEquals(listOf("gemini-tts"), result.models.map { it.id })
        assertEquals(1, result.excludedCount)
        assertEquals(2, urls.size)
        assertTrue(urls.all { it.startsWith("https://api.example.test/v1/models?") })
    }
    @Test fun failedAuthenticationCannotBecomePresetSuccess() = runBlocking {
        val catalog = SpeechModelCatalog(fakeClient { 401 to """{"error":"bad key"}""" })
        val failure = runCatching { catalog.fetch(source()) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("401"))
        assertFalse(failure.message!!.contains("test-secret"))
    }
    @Test fun repeatedPaginationIsRejected() = runBlocking {
        var calls = 0
        val catalog = SpeechModelCatalog(fakeClient {
            calls++; 200 to """{"models":[],"nextPageToken":"same"}"""
        })
        assertTrue(runCatching { catalog.fetch(source(SpeechModelService.GOOGLE)) }.isFailure)
        assertEquals(2, calls)
    }
}
