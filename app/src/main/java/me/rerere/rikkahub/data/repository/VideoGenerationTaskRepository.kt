package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.dao.VideoGenerationTaskDAO
import me.rerere.rikkahub.data.db.entity.VideoGenerationOutputEntity
import me.rerere.rikkahub.data.db.entity.VideoGenerationTaskEntity
import me.rerere.rikkahub.data.model.VideoGenerationTaskState

class VideoGenerationTaskRepository(
    private val dao: VideoGenerationTaskDAO,
) {
    fun observeAll(): Flow<List<VideoGenerationTaskEntity>> = dao.observeAll()

    fun observeOutputs(taskId: String): Flow<List<VideoGenerationOutputEntity>> =
        dao.observeOutputs(taskId)

    suspend fun getTask(id: String): VideoGenerationTaskEntity? = dao.getTask(id)

    suspend fun getOutputs(taskId: String): List<VideoGenerationOutputEntity> =
        dao.getOutputs(taskId)

    suspend fun getRecoverableTasks(): List<VideoGenerationTaskEntity> =
        dao.getTasksInStates(VideoGenerationTaskState.entries.filterTo(mutableSetOf()) { it.isRecoverable })

    suspend fun upsertTask(task: VideoGenerationTaskEntity) = dao.upsertTask(task)

    suspend fun replaceOutputs(taskId: String, outputs: List<VideoGenerationOutputEntity>) =
        dao.replaceOutputs(taskId, outputs)

    suspend fun upsertOutput(output: VideoGenerationOutputEntity) = dao.upsertOutput(output)

    suspend fun updateDownloadedOutput(
        taskId: String,
        outputIndex: Int,
        localRelativePath: String,
        sizeBytes: Long,
        mimeType: String,
        displayName: String,
    ): Boolean = dao.updateDownloadedOutput(
        taskId = taskId,
        outputIndex = outputIndex,
        localRelativePath = localRelativePath,
        sizeBytes = sizeBytes,
        mimeType = mimeType,
        displayName = displayName,
    ) == 1

    suspend fun bindRemoteTaskId(
        id: String,
        remoteTaskId: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        require(remoteTaskId.isNotBlank()) { "Remote video task id cannot be blank" }
        val current = dao.getTask(id) ?: return false
        require(current.status == VideoGenerationTaskState.SUBMITTING ||
            current.status == VideoGenerationTaskState.CANCELING) {
            "Cannot bind remote video task id while task is ${current.status}"
        }
        return dao.compareAndSetRemoteTaskId(
            id = id,
            expected = current.status,
            remoteTaskId = remoteTaskId,
            updatedAt = now,
        ) == 1
    }

    suspend fun transition(
        id: String,
        target: VideoGenerationTaskState,
        progressPercent: Int? = null,
        errorCode: String? = null,
        errorMessage: String? = null,
        retryable: Boolean = false,
        nextPollAt: Long? = null,
        now: Long = System.currentTimeMillis(),
        attemptDelta: Int = 0,
    ): Boolean {
        val current = dao.getTask(id) ?: return false
        if (!current.status.canTransitionTo(target)) return false
        val completedAt = now.takeIf { target.isTerminal }
        return dao.compareAndSetState(
            id = id,
            expected = current.status,
            target = target,
            progressPercent = progressPercent?.coerceIn(0, 100),
            errorCode = errorCode,
            errorMessage = errorMessage,
            retryable = retryable,
            nextPollAt = nextPollAt,
            updatedAt = now,
            completedAt = completedAt,
            clearRemoteTaskId = target == VideoGenerationTaskState.SUBMITTING,
            attemptDelta = attemptDelta.coerceAtLeast(0),
        ) == 1
    }

    suspend fun deleteTask(id: String): Boolean = dao.deleteTask(id) > 0
}
