package me.rerere.ai.provider

import kotlinx.serialization.Serializable
import me.rerere.ai.provider.providers.google.isGeminiModel
import me.rerere.ai.provider.providers.openai.isAlibabaModelStudioHost
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Responses media parts are gateway extensions, not a Google/OpenAI endpoint guarantee. */
@Serializable
enum class ResponsesMediaMode { AUTO, DISABLED, GEMINI_EXTENSIONS }

data class MediaInputSupport(
    val audio: Boolean = false,
    val video: Boolean = false,
    val responsesExtensions: Boolean = false,
    val maxInlineBytes: Long? = null,
    val audioFormats: Set<String>? = null,
)

// Reserve room for base64 expansion, text, images and JSON inside a 20 MiB request.
const val RESPONSES_MEDIA_MAX_INLINE_BYTES = 14L * 1024 * 1024
const val RESPONSES_MEDIA_MAX_REQUEST_BYTES = 20L * 1024 * 1024

fun ProviderSetting.OpenAI.canUseGeminiResponsesMedia(): Boolean {
    if (!useResponseApi) return false
    val host = baseUrl.toHttpUrlOrNull()?.host ?: return false
    // Neither Google's OpenAI-compatible endpoint nor OpenAI hosts Gemini Responses models.
    if (officialChatVendor(baseUrl) != OfficialChatVendor.OTHER ||
        host.endsWith(".googleapis.com") || isAlibabaModelStudioHost(host)
    ) return false
    return when (responsesMediaMode) {
        ResponsesMediaMode.DISABLED -> false
        ResponsesMediaMode.AUTO -> host == "openrouter.ai"
        ResponsesMediaMode.GEMINI_EXTENSIONS -> true
    }
}

/** Shared by the attachment picker and wire encoder, including model-level provider overrides. */
fun resolveMediaInputSupport(model: Model?, provider: ProviderSetting?): MediaInputSupport {
    if (model == null || model.type != ModelType.CHAT) return MediaInputSupport()
    val effectiveProvider = model.providerOverwrite ?: provider
    if (effectiveProvider is ProviderSetting.Google) {
        // Retain the existing Google/Interactions attachment behavior in this first phase.
        return MediaInputSupport(audio = true, video = true)
    }
    val modelId = model.parameterModelId().lowercase()
    val specialPurpose = Regex("(?:^|[-_/ .])(embedding|tts|live|image|robotics)(?:[-_/ .]|$)")
        .containsMatchIn(modelId)
    if (effectiveProvider is ProviderSetting.OpenAI && model.isGeminiModel() && !specialPurpose &&
        effectiveProvider.canUseGeminiResponsesMedia()
    ) return MediaInputSupport(
        audio = true,
        video = true,
        responsesExtensions = true,
        maxInlineBytes = RESPONSES_MEDIA_MAX_INLINE_BYTES,
        audioFormats = if (effectiveProvider.baseUrl.toHttpUrlOrNull()?.host == "openrouter.ai") {
            setOf("wav", "mp3")
        } else {
            setOf("wav", "mp3", "aiff", "aac", "ogg", "flac", "m4a", "opus", "webm")
        },
    )
    return MediaInputSupport()
}
