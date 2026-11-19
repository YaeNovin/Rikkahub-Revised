package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.*
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.parameterModelId

fun isGeminiArgon(modelId: String): Boolean = ModelRegistry.GEMINI_4_ARGON.match(modelId)

fun Model.isGeminiHydrogen(): Boolean = ModelRegistry.GEMINI_4_HYDROGEN.match(parameterModelId())
fun Model.gemini4DefaultThinking(): String = if (isGeminiHydrogen() ||
    parameterModelId().lowercase().contains("carbon-cyber-permissive")) "high" else "medium"

fun Model.gemini4ThinkingLevels(): Set<String> =
    if (isGeminiHydrogen()) setOf("low", "medium", "high", "xhigh") else setOf("low", "medium", "high")

/** Argon's sampling and explicit-cache settings are deprecated, even in custom bodies. */
internal fun normalizeArgonRequest(body: JsonObject, model: Model = Model(modelId = "gemini-4-argon")): JsonObject {
    val generation = body["generationConfig"] as? JsonObject ?: JsonObject(emptyMap())
    val thinking = generation["thinkingConfig"] as? JsonObject ?: JsonObject(emptyMap())
    val level = (thinking["thinkingLevel"] as? JsonPrimitive)?.contentOrNull
        ?.lowercase()?.takeIf { it in model.gemini4ThinkingLevels() } ?: model.gemini4DefaultThinking()
    return JsonObject(body.filterKeys { it !in setOf("cachedContent", "cached_content") } +
        ("generationConfig" to JsonObject(generation.filterKeys {
            it !in setOf("temperature", "topP", "topK", "candidateCount", "top_p", "top_k", "candidate_count", "presencePenalty", "frequencyPenalty")
        } + ("thinkingConfig" to JsonObject(thinking.filterKeys { it != "thinkingBudget" } +
            ("thinkingLevel" to JsonPrimitive(level)))))))
}

/** A token at MAX_TOKENS is a checkpoint for explicit resume, never permission to exceed the budget. */
internal fun nextArgonContinuation(candidate: JsonObject, previousTokens: MutableSet<String>): String? {
    if ((candidate["finishReason"] as? JsonPrimitive)?.contentOrNull != "CONTINUATION") return null
    val token = (candidate["continuationToken"] as? JsonPrimitive)?.contentOrNull
    require(!token.isNullOrBlank()) { "Gemini Argon continuation response is missing its token" }
    require(previousTokens.size < 64 && previousTokens.add(token)) { "Gemini Argon repeated its continuation checkpoint" }
    return token
}

internal fun JsonObject.withArgonContinuation(token: String?): JsonObject =
    if (token == null) this else JsonObject(this + ("continuationToken" to JsonPrimitive(token)))

/** A continuation is the same text block; do not insert a newline at every HTTP hop. */
internal fun appendGoogleContinuationParts(first: List<UIMessagePart>, second: List<UIMessagePart>): List<UIMessagePart> {
    val result = first.toMutableList()
    for (part in second) {
        val previous = result.lastOrNull()
        if (previous is UIMessagePart.Text && part is UIMessagePart.Text && previous.metadata == part.metadata) {
            result[result.lastIndex] = previous.copy(text = previous.text + part.text)
        } else result += part
    }
    return result
}

internal fun addGoogleHopUsage(first: TokenUsage?, second: TokenUsage?): TokenUsage? = when {
    first == null -> second
    second == null -> first
    else -> TokenUsage(
        promptTokens = first.promptTokens + second.promptTokens,
        completionTokens = first.completionTokens + second.completionTokens,
        cachedTokens = first.cachedTokens + second.cachedTokens,
        totalTokens = first.totalTokens + second.totalTokens,
    )
}
