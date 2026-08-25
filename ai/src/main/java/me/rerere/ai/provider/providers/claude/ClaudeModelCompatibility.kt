package me.rerere.ai.provider.providers.claude

import me.rerere.ai.core.ReasoningLevel

data class ClaudeModelParameterSupport(
    val available: Boolean,
    val supportsAdaptiveThinking: Boolean,
    val requiresAdaptiveThinking: Boolean,
    val supportsManualThinking: Boolean,
    val supportsEffort: Boolean,
    val supportsXHighEffort: Boolean,
    val supportsMaxEffort: Boolean,
    val supportsServiceTier: Boolean,
    val supportsInferenceGeo: Boolean,
    val supportsSamplingParameters: Boolean,
    val supportsStructuredOutput: Boolean,
)

fun resolveClaudeModelParameterSupport(modelId: String): ClaudeModelParameterSupport {
    val normalized = modelId.normalizedClaudeModelId()
    val model = parseClaudeModel(normalized)
    val family = model?.family
    val version = model?.version
    val available = CLAUDE_MODEL_MARKER.containsMatchIn(normalized) && when (family) {
        "fable" -> version == ClaudeVersion(5)
        "sonnet" -> version == ClaudeVersion(4, 6) || version == ClaudeVersion(5)
        "opus" -> version in CURRENT_OPUS_VERSIONS
        else -> false
    }
    val is47OrLater = version?.atLeast(4, 7) == true
    val isVersion5OrLater = version?.major?.let { it >= 5 } == true
    val supportsEffort = available
    val supportsXHighEffort = available && when (family) {
        "fable" -> isVersion5OrLater
        "opus" -> isVersion5OrLater || is47OrLater
        "sonnet" -> isVersion5OrLater
        else -> false
    }

    return ClaudeModelParameterSupport(
        available = available,
        supportsAdaptiveThinking = available,
        requiresAdaptiveThinking = available && family == "fable" && version == ClaudeVersion(5),
        supportsManualThinking = false,
        supportsEffort = supportsEffort,
        supportsXHighEffort = supportsXHighEffort,
        supportsMaxEffort = available,
        supportsServiceTier = available && !(
            (family == "opus" || family == "sonnet") && isVersion5OrLater
            ),
        supportsInferenceGeo = available,
        supportsSamplingParameters = available && version == ClaudeVersion(4, 6),
        supportsStructuredOutput = available,
    )
}

internal fun resolveAnthropicReasoningEffort(
    modelId: String,
    level: ReasoningLevel,
): String? {
    val support = resolveClaudeModelParameterSupport(modelId)
    if (!support.supportsEffort || level == ReasoningLevel.OFF || level == ReasoningLevel.AUTO) {
        return null
    }
    return when (level) {
        ReasoningLevel.LOW -> "low"
        ReasoningLevel.MEDIUM -> "medium"
        ReasoningLevel.HIGH -> "high"
        ReasoningLevel.XHIGH -> if (support.supportsXHighEffort) "xhigh" else "high"
        ReasoningLevel.MAX -> if (support.supportsMaxEffort) "max" else "high"
        ReasoningLevel.OFF,
        ReasoningLevel.AUTO -> null
    }
}

private data class ClaudeModel(
    val family: String,
    val version: ClaudeVersion,
)

private data class ClaudeVersion(val major: Int, val minor: Int = 0) {
    fun atLeast(requiredMajor: Int, requiredMinor: Int): Boolean =
        major > requiredMajor || (major == requiredMajor && minor >= requiredMinor)
}

private fun parseClaudeModel(normalized: String): ClaudeModel? {
    FAMILY_FIRST.find(normalized)?.let { match ->
        val minorText = match.groupValues[3]
        return ClaudeModel(
            family = match.groupValues[1],
            version = ClaudeVersion(
                major = match.groupValues[2].toInt(),
                minor = minorText.takeUnless { it.length == 8 }?.toIntOrNull() ?: 0,
            ),
        )
    }
    VERSION_FIRST.find(normalized)?.let { match ->
        return ClaudeModel(
            family = match.groupValues[3],
            version = ClaudeVersion(
                major = match.groupValues[1].toInt(),
                minor = match.groupValues[2].toIntOrNull() ?: 0,
            ),
        )
    }
    return null
}

private fun String.normalizedClaudeModelId(): String = lowercase()
    .replace('.', '-')
    .replace('_', '-')
    .replace('@', '-')

private val CLAUDE_MODEL_MARKER = Regex("(?:^|[-./:])claude(?:[-./:]|$)")
private val CURRENT_OPUS_VERSIONS = setOf(
    ClaudeVersion(4, 6),
    ClaudeVersion(4, 7),
    ClaudeVersion(4, 8),
    ClaudeVersion(5),
)
private val FAMILY_FIRST = Regex("claude-(opus|sonnet|haiku|fable|mythos)-(\\d+)(?:-(\\d+))?")
private val VERSION_FIRST = Regex("claude-(\\d+)(?:-(\\d+))?-(opus|sonnet|haiku|fable|mythos)")
