package me.rerere.rikkahub.data.model

import me.rerere.ai.provider.VideoGenerationTaskStatus

enum class VideoGenerationTaskState {
    CREATED,
    SUBMITTING,
    QUEUED,
    RUNNING,
    DOWNLOADING,
    SUCCEEDED,
    FAILED,
    CANCELING,
    CANCELED,
    EXPIRED,
    ;

    val isTerminal: Boolean
        get() = this in setOf(SUCCEEDED, FAILED, CANCELED, EXPIRED)

    val isRecoverable: Boolean
        get() = this in setOf(CREATED, SUBMITTING, QUEUED, RUNNING, DOWNLOADING, CANCELING)

    fun canTransitionTo(target: VideoGenerationTaskState): Boolean {
        if (target == this) return true
        return target in allowedTransitions.getValue(this)
    }
}

fun VideoGenerationTaskStatus.toLocalState(): VideoGenerationTaskState = when (this) {
    VideoGenerationTaskStatus.QUEUED -> VideoGenerationTaskState.QUEUED
    VideoGenerationTaskStatus.RUNNING -> VideoGenerationTaskState.RUNNING
    VideoGenerationTaskStatus.SUCCEEDED -> VideoGenerationTaskState.DOWNLOADING
    VideoGenerationTaskStatus.FAILED -> VideoGenerationTaskState.FAILED
    VideoGenerationTaskStatus.CANCELED -> VideoGenerationTaskState.CANCELED
    VideoGenerationTaskStatus.EXPIRED -> VideoGenerationTaskState.EXPIRED
}

private val allowedTransitions = mapOf(
    VideoGenerationTaskState.CREATED to setOf(
        VideoGenerationTaskState.SUBMITTING,
        VideoGenerationTaskState.FAILED,
        VideoGenerationTaskState.CANCELED,
        VideoGenerationTaskState.EXPIRED,
    ),
    VideoGenerationTaskState.SUBMITTING to setOf(
        VideoGenerationTaskState.QUEUED,
        VideoGenerationTaskState.RUNNING,
        VideoGenerationTaskState.DOWNLOADING,
        VideoGenerationTaskState.FAILED,
        VideoGenerationTaskState.CANCELING,
        VideoGenerationTaskState.CANCELED,
        VideoGenerationTaskState.EXPIRED,
    ),
    VideoGenerationTaskState.QUEUED to setOf(
        VideoGenerationTaskState.RUNNING,
        VideoGenerationTaskState.DOWNLOADING,
        VideoGenerationTaskState.FAILED,
        VideoGenerationTaskState.CANCELING,
        VideoGenerationTaskState.CANCELED,
        VideoGenerationTaskState.EXPIRED,
    ),
    VideoGenerationTaskState.RUNNING to setOf(
        VideoGenerationTaskState.DOWNLOADING,
        VideoGenerationTaskState.FAILED,
        VideoGenerationTaskState.CANCELING,
        VideoGenerationTaskState.CANCELED,
        VideoGenerationTaskState.EXPIRED,
    ),
    VideoGenerationTaskState.DOWNLOADING to setOf(
        VideoGenerationTaskState.SUCCEEDED,
        VideoGenerationTaskState.FAILED,
        VideoGenerationTaskState.CANCELED,
        VideoGenerationTaskState.EXPIRED,
    ),
    VideoGenerationTaskState.SUCCEEDED to emptySet(),
    VideoGenerationTaskState.FAILED to setOf(
        VideoGenerationTaskState.DOWNLOADING,
        VideoGenerationTaskState.RUNNING,
        VideoGenerationTaskState.SUBMITTING,
        VideoGenerationTaskState.CANCELED,
    ),
    VideoGenerationTaskState.CANCELING to setOf(
        VideoGenerationTaskState.QUEUED,
        VideoGenerationTaskState.RUNNING,
        VideoGenerationTaskState.DOWNLOADING,
        VideoGenerationTaskState.FAILED,
        VideoGenerationTaskState.CANCELED,
        VideoGenerationTaskState.EXPIRED,
    ),
    VideoGenerationTaskState.CANCELED to setOf(VideoGenerationTaskState.SUBMITTING),
    VideoGenerationTaskState.EXPIRED to setOf(VideoGenerationTaskState.SUBMITTING),
)
