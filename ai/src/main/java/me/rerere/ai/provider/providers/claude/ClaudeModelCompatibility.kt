package me.rerere.ai.provider.providers.claude

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.normalizeCompactVendorModelId

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
        "mythos" -> version?.major == 5 || "preview" in normalized
        "sonnet" -> version == ClaudeVersion(3, 7) ||
            version == ClaudeVersion(4) ||
            version == ClaudeVersion(4, 5) ||
            version?.atLeast(4, 6) == true
        "opus" -> version == ClaudeVersion(4) ||
            version == ClaudeVersion(4, 1) ||
            version == ClaudeVersion(4, 5) ||
            version?.atLeast(4, 6) == true
        "haiku" -> version == ClaudeVersion(4, 5)
        else -> false
    }
    val supportsAdaptiveThinking = available && (
        version?.atLeast(4, 6) == true ||
            (family == "mythos" && "preview" in normalized) ||
            version?.major?.let { it >= 5 } == true
        )
    val supportsManualThinking = available && !supportsAdaptiveThinking
    val is47OrLater = version?.atLeast(4, 7) == true
    val isVersion5OrLater = version?.major?.let { it >= 5 } == true
    val supportsEffort = available && (
        supportsAdaptiveThinking ||
            (family == "opus" && version == ClaudeVersion(4, 5))
        )
    val supportsXHighEffort = available && when (family) {
        "fable" -> isVersion5OrLater
        "opus" -> isVersion5OrLater || is47OrLater
        "sonnet" -> isVersion5OrLater
        else -> false
    }

    return ClaudeModelParameterSupport(
        available = available,
        supportsAdaptiveThinking = supportsAdaptiveThinking,
        requiresAdaptiveThinking = supportsAdaptiveThinking &&
            family == "fable" && version == ClaudeVersion(5),
        supportsManualThinking = supportsManualThinking,
        supportsEffort = supportsEffort,
        supportsXHighEffort = supportsXHighEffort,
        supportsMaxEffort = supportsAdaptiveThinking,
        supportsServiceTier = supportsAdaptiveThinking && !(
            (family == "opus" || family == "sonnet") && isVersion5OrLater
            ),
        supportsInferenceGeo = supportsAdaptiveThinking,
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
        ReasoningLevel.MINIMAL,
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
    if ("claude-mythos-preview" in normalized) {
        return ClaudeModel(family = "mythos", version = ClaudeVersion(5))
    }
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
    .replace(Regex("\\s+"), "-")
    .replace(Regex("claude(?=\\d)"), "claude-")
    .normalizeCompactVendorModelId()

private val CLAUDE_MODEL_MARKER = Regex("(?:^|[-./:])claude(?:[-./:]|$)")
private val FAMILY_FIRST = Regex("claude-(opus|sonnet|haiku|fable|mythos)-(\\d+)(?:-(\\d+))?")
private val VERSION_FIRST = Regex("claude-(\\d+)(?:-(\\d+))?-(opus|sonnet|haiku|fable|mythos)")
