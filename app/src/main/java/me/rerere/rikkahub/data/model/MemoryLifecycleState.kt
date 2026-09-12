package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Lifecycle of a saved memory. Only active memories are used for normal retrieval. */
@Serializable
enum class MemoryLifecycleState {
    @SerialName("active")
    ACTIVE,

    @SerialName("completed")
    COMPLETED,

    @SerialName("superseded")
    SUPERSEDED,
    ;

    companion object {
        fun fromWireName(value: String?): MemoryLifecycleState = when (value?.lowercase()) {
            "completed" -> COMPLETED
            "superseded", "superseded_by_newer" -> SUPERSEDED
            else -> ACTIVE
        }
    }
}
