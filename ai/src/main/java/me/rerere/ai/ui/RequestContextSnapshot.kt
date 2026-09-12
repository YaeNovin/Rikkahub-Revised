package me.rerere.ai.ui

import kotlinx.serialization.Serializable

/** Local accounting metadata, never serialized as provider message content. */
@Serializable
data class RequestContextSnapshot(
    val modelId: String,
    val historyFingerprint: String,
    val scopeKey: String?,
    val estimatedInput: Map<String, Int>,
    val sentAssistant: Map<String, Int>,
    val measuredPromptTokens: Int? = null,
    val measuredAt: Long = 0,
    val responseFingerprint: String? = null,
    val includeReasoning: Boolean = false,
)
