package me.rerere.rikkahub.data.memory

sealed interface MemoryExtractionStatus {
    data object Idle : MemoryExtractionStatus

    data class Running(
        val startedAt: Long = System.currentTimeMillis(),
    ) : MemoryExtractionStatus

    data class Queued(
        val pendingUserTurns: Int,
    ) : MemoryExtractionStatus

    data class Completed(
        val savedCount: Int,
        val candidateCount: Int,
        val completedAt: Long = System.currentTimeMillis(),
    ) : MemoryExtractionStatus

    data class NoChanges(
        val reason: MemoryExtractionOutcome.Reason,
        val candidateCount: Int = 0,
        val completedAt: Long = System.currentTimeMillis(),
    ) : MemoryExtractionStatus

    data class Failed(
        val message: String,
        val completedAt: Long = System.currentTimeMillis(),
    ) : MemoryExtractionStatus
}
