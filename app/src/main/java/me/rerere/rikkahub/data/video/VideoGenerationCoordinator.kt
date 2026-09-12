package me.rerere.rikkahub.data.video

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.db.entity.VideoGenerationTaskEntity
import me.rerere.rikkahub.data.model.VideoGenerationRequestState
import me.rerere.rikkahub.data.model.VideoGenerationTaskState
import me.rerere.rikkahub.data.repository.VideoGenerationTaskRepository
import me.rerere.rikkahub.utils.JsonInstant
import java.util.UUID
import java.util.concurrent.TimeUnit

class VideoGenerationCoordinator(
    context: Context,
    private val repository: VideoGenerationTaskRepository,
    private val providerManager: me.rerere.ai.provider.ProviderManager,
) {
    private val workManager = WorkManager.getInstance(context)

    suspend fun createTask(
        provider: ProviderSetting,
        model: Model,
        request: VideoGenerationRequestState,
    ): String {
        require(provider.enabled) { "Video provider is disabled" }
        require(provider.models.any { it.id == model.id }) {
            "Video model does not belong to the selected provider"
        }
        require(request.prompt.isNotBlank()) { "Video prompt cannot be empty" }
        val constraints = providerManager.videoGenerationConstraints(provider, model)
        require(constraints.supportsGeneration) { "供应商或模型不支持视频生成" }
        require(request.inputMode in constraints.supportedInputModes) { "模型不支持所选生成方式" }
        val params = request.toParams(model, constraints)
        if (request.inputMode == me.rerere.ai.provider.VideoGenerationInputMode.IMAGE_TO_VIDEO) {
            require(params.referenceImages.isNotEmpty()) { "请选择参考图片" }
        }
        if (request.inputMode == me.rerere.ai.provider.VideoGenerationInputMode.KEYFRAMES_TO_VIDEO) {
            require(params.referenceImages.map { it.role }.containsAll(listOf(
                me.rerere.ai.provider.VideoReferenceImageRole.FIRST_FRAME,
                me.rerere.ai.provider.VideoReferenceImageRole.LAST_FRAME))) { "请选择首帧和尾帧图片" }
        }
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        repository.upsertTask(
            VideoGenerationTaskEntity(
                id = id,
                providerId = provider.id.toString(),
                providerName = provider.name,
                modelId = model.id.toString(),
                modelApiId = model.modelId,
                prompt = request.prompt.trim(),
                requestJson = JsonInstant.encodeToString(request),
                createdAt = now,
                updatedAt = now,
            ),
        )
        VideoGenerationWorkScheduler.enqueue(workManager, id, ExistingWorkPolicy.KEEP)
        return id
    }

    suspend fun cancelTask(id: String): Boolean {
        val task = repository.getTask(id) ?: return false
        if (task.status.isTerminal) return false
        if (task.status !in setOf(VideoGenerationTaskState.CREATED, VideoGenerationTaskState.DOWNLOADING)) {
            if (task.modelApiId.trim() in me.rerere.ai.provider.providers.openai.MINIMAX_VIDEO_MODELS) return false
            val settings = task.modelApiId
            if (settings.startsWith("grok-imagine-video") || settings.startsWith("kling-v") ||
                settings in setOf("as-sd2.0-fast", "video-ds-2.0", "video-ds-2.0-fast")) return false
        }
        if (task.status == VideoGenerationTaskState.DOWNLOADING) {
            val canceled = repository.transition(id, VideoGenerationTaskState.CANCELED)
            if (canceled) workManager.cancelUniqueWork(workName(id))
            return canceled
        }
        if (task.status == VideoGenerationTaskState.SUBMITTING) {
            return repository.transition(id, VideoGenerationTaskState.CANCELING)
        }
        if (task.remoteTaskId == null && task.status == VideoGenerationTaskState.CREATED) {
            workManager.cancelUniqueWork(workName(id))
            return repository.transition(id, VideoGenerationTaskState.CANCELED)
        }
        if (!repository.transition(id, VideoGenerationTaskState.CANCELING)) return false
        VideoGenerationWorkScheduler.enqueue(workManager, id, ExistingWorkPolicy.REPLACE)
        return true
    }

    suspend fun retryTask(id: String): Boolean {
        val task = repository.getTask(id) ?: return false
        if (task.status !in setOf(
                VideoGenerationTaskState.FAILED,
                VideoGenerationTaskState.CANCELED,
                VideoGenerationTaskState.EXPIRED,
            )
        ) return false
        if (task.status != VideoGenerationTaskState.FAILED || task.remoteTaskId == null) return false
        val target = if (repository.getOutputs(id).isNotEmpty()) {
            VideoGenerationTaskState.DOWNLOADING
        } else VideoGenerationTaskState.RUNNING
        if (!repository.transition(
                id = id,
                target = target,
            )
        ) return false
        VideoGenerationWorkScheduler.enqueue(workManager, id, ExistingWorkPolicy.REPLACE)
        return true
    }

    suspend fun recoverTasks() {
        repository.getRecoverableTasks().forEach { task ->
            VideoGenerationWorkScheduler.enqueue(workManager, task.id, ExistingWorkPolicy.KEEP)
        }
    }

    private fun workName(id: String): String = VideoGenerationWorkScheduler.workName(id)
}

internal object VideoGenerationWorkScheduler {
    private const val WORK_TAG = "video-generation"

    fun enqueue(
        workManager: WorkManager,
        id: String,
        policy: ExistingWorkPolicy,
        delayMillis: Long = 0L,
    ) {
        val request = OneTimeWorkRequestBuilder<VideoGenerationWorker>()
            .setInputData(workDataOf(VideoGenerationWorker.KEY_TASK_ID to id))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .addTag(WORK_TAG)
            .addTag("$WORK_TAG:$id")
            .build()
        workManager.enqueueUniqueWork(workName(id), policy, request)
    }

    fun enqueueNext(workManager: WorkManager, id: String, delayMillis: Long) {
        enqueue(
            workManager = workManager,
            id = id,
            policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
            delayMillis = delayMillis,
        )
    }

    fun workName(id: String): String = "$WORK_TAG:$id"
}
