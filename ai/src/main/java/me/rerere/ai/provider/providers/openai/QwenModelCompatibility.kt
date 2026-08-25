package me.rerere.ai.provider.providers.openai

data class QwenModelParameterSupport(
    val available: Boolean,
    val supportsJsonSchema: Boolean,
    val supportsToolStream: Boolean,
    val supportsPreserveThinking: Boolean,
    val supportsHighResolutionVision: Boolean,
)

fun resolveQwenModelParameterSupport(modelId: String): QwenModelParameterSupport {
    val normalized = modelId.normalizedQwenModelId()
    val available = normalized.isQwenFamily() && !normalized.isQwenSpecializedMediaModel()
    return QwenModelParameterSupport(
        available = available,
        supportsJsonSchema = available && QWEN_JSON_SCHEMA_MODELS.containsMatchIn(normalized),
        supportsToolStream = available && QWEN_TOOL_STREAM_MODELS.any { it.containsMatchIn(normalized) },
        supportsPreserveThinking = available && QWEN_PRESERVE_THINKING_MODELS.any {
            it.containsMatchIn(normalized)
        },
        supportsHighResolutionVision = available && normalized.isQwenVisionFamily(),
    )
}

fun isAlibabaModelStudioHost(host: String): Boolean {
    val normalized = host.trim().lowercase()
    return normalized == "dashscope.aliyuncs.com" ||
        (normalized.startsWith("dashscope-") && normalized.endsWith(".aliyuncs.com")) ||
        normalized.endsWith(".dashscope.aliyuncs.com") ||
        normalized.endsWith(".maas.aliyuncs.com")
}

internal fun String.normalizedQwenModelId(): String =
    substringAfterLast('/').substringAfterLast(':').trim().lowercase().replace('_', '-')

private fun String.isQwenFamily(): Boolean =
    startsWith("qwen") || startsWith("qwq") || startsWith("qvq")

private fun String.isQwenSpecializedMediaModel(): Boolean =
    startsWith("qwen-image") ||
        startsWith("qwen-video") ||
        startsWith("qwen-audio") ||
        QWEN_NON_CHAT_MARKERS.any(::contains)

private fun String.isQwenVisionFamily(): Boolean =
    startsWith("qvq") ||
        contains("-vl") ||
        contains("omni") ||
        QWEN_NATIVE_MULTIMODAL_MODELS.containsMatchIn(this)

private val QWEN_JSON_SCHEMA_MODELS =
    Regex("^qwen3[.-](?:7-(?:plus|max)|8-max)(?:[-.]|$)")

private val QWEN_TOOL_STREAM_MODELS = listOf(
    Regex("^qwen3[.-](?:7|8)-max(?:[-.]|$)"),
    Regex("^qwen3[.-](?:6|7)-plus(?:[-.]|$)"),
    Regex("^qwen3[.-]5-plus(?:[-.]|$)"),
    Regex("^qwen3[.-](?:6|7)-flash(?:[-.]|$)"),
    Regex("^qwen3[.-]5-flash(?:[-.]|$)"),
)

private val QWEN_PRESERVE_THINKING_MODELS = listOf(
    Regex("^qwen3[.-]8-max(?:[-.]|$)"),
    Regex("^qwen3[.-](?:6|7)-(?:max|plus|flash)(?:[-.]|$)"),
)

private val QWEN_NATIVE_MULTIMODAL_MODELS =
    Regex("^qwen3[.-](?:5|6|7|8)-(?:max|plus|flash)(?:[-.]|$)")

private val QWEN_NON_CHAT_MARKERS = setOf(
    "-image",
    "-video",
    "-audio",
    "embedding",
    "rerank",
    "realtime",
    "-tts",
    "-asr",
    "speech-synthesis",
    "speech-recognition",
)
