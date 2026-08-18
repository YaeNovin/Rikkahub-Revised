package me.rerere.ai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ReasoningLevel(
    val budgetTokens: Int,
    val effort: String
) {
    @SerialName("off")
    OFF(0, "none"),
    @SerialName("auto")
    AUTO(-1, "auto"),
    @SerialName("low")
    LOW(1_000, "low"),
    @SerialName("medium")
    MEDIUM(2_000, "medium"),
    @SerialName("high")
    HIGH(8_000, "high"),
    @SerialName("xhigh")
    XHIGH(16_000, "xhigh"),
    @SerialName("max")
    MAX(32_000, "max");

    val isEnabled: Boolean
        get() = this != OFF

    companion object {
        fun fromBudgetTokens(budgetTokens: Int?): ReasoningLevel {
            return entries.minByOrNull { kotlin.math.abs(it.budgetTokens - (budgetTokens ?: AUTO.budgetTokens)) } ?: AUTO
        }
    }
}

private val ENABLED_REASONING_LEVELS = listOf(
    ReasoningLevel.LOW,
    ReasoningLevel.MEDIUM,
    ReasoningLevel.HIGH,
    ReasoningLevel.XHIGH,
    ReasoningLevel.MAX,
)

internal fun ReasoningLevel.cappedEffort(maximum: ReasoningLevel): String? {
    if (this == ReasoningLevel.AUTO) return null
    if (this == ReasoningLevel.OFF) return ReasoningLevel.OFF.effort

    val requestedIndex = ENABLED_REASONING_LEVELS.indexOf(this).coerceAtLeast(0)
    val maximumIndex = ENABLED_REASONING_LEVELS.indexOf(maximum).coerceAtLeast(0)
    return ENABLED_REASONING_LEVELS[minOf(requestedIndex, maximumIndex)].effort
}

internal fun ReasoningLevel.cappedBudget(maximumTokens: Int): Int? = when (this) {
    ReasoningLevel.AUTO -> null
    else -> budgetTokens.coerceAtMost(maximumTokens)
}
