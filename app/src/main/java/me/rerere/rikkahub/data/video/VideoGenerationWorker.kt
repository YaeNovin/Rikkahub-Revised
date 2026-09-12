package me.rerere.rikkahub.data.video

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderRequestChannel
import me.rerere.ai.provider.ProviderRequestDiagnostics
import me.rerere.ai.provider.ProviderRequestOperation
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.VideoGenerationOutput
import me.rerere.ai.provider.VideoGenerationTaskSnapshot
import me.rerere.ai.provider.VideoGenerationTaskStatus
import me.rerere.ai.provider.isRetryableProviderFailure
import me.rerere.ai.provider.providerRequestFailure
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.VideoGenerationOutputEntity
import me.rerere.rikkahub.data.db.entity.VideoGenerationTaskEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.VideoGenerationRequestState
import me.rerere.rikkahub.data.model.VideoGenerationTaskState
import me.rerere.rikkahub.data.repository.VideoGenerationTaskRepository
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.OkHttpClient
import okhttp3.Request
import me.rerere.common.http.awaitAndUse
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import kotlin.uuid.Uuid
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID

class VideoGenerationWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
    private val repository: VideoGenerationTaskRepository,
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val filesManager: FilesManager,
    private val client: OkHttpClient,
) : CoroutineWorker(appContext, workerParameters) {
    private val workManager = WorkManager.getInstance(appContext)

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_TASK_ID)?.takeIf(String::isNotBlank)
            ?: return Result.failure()
        return try {
            process(id)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            handleFailure(id, error)
        }
    }

    private suspend fun process(id: String): Result {
        var task = repository.getTask(id) ?: return Result.success()
        if (task.status.isTerminal) return Result.success()
        if (task.status == VideoGenerationTaskState.SUBMITTING && task.remoteTaskId == null) {
            repository.transition(id, VideoGenerationTaskState.FAILED,
                errorCode = "creation_result_unknown",
                errorMessage = "Submission was interrupted. Check the provider task history before generating again.")
            return Result.success()
        }
        if (System.currentTimeMillis() - task.createdAt >= REMOTE_TASK_RETENTION_MILLIS) {
            repository.transition(
                id = id,
                target = VideoGenerationTaskState.EXPIRED,
                errorCode = "task_expired",
                errorMessage = "Video task exceeded the remote retention window",
            )
            return Result.success()
        }

        val (provider, model) = resolveProviderAndModel(task)
        when (task.status) {
            VideoGenerationTaskState.CREATED -> {
                if (!repository.transition(id, VideoGenerationTaskState.SUBMITTING)) {
                    return Result.success()
                }
                task = repository.getTask(id) ?: return Result.success()
            }

            VideoGenerationTaskState.CANCELING -> return cancelRemote(task, provider, model)
            VideoGenerationTaskState.DOWNLOADING -> {
                val remoteId = task.remoteTaskId ?: error("Video task has no remote id")
                val snapshot = providerManager.getVideoGenerationTask(provider, model, remoteId)
                return applySnapshot(task, provider, snapshot)
            }
            else -> Unit
        }

        if (task.status == VideoGenerationTaskState.SUBMITTING && task.remoteTaskId == null) {
            val requestState = JsonInstant.decodeFromString<VideoGenerationRequestState>(task.requestJson)
            val constraints = providerManager.videoGenerationConstraints(provider, model)
            check(constraints.supportsGeneration) { "Selected provider no longer supports video generation" }
            val snapshot = providerManager.createVideoGenerationTask(
                setting = provider,
                params = requestState.toParams(model, constraints),
            )
            check(repository.bindRemoteTaskId(id, snapshot.taskId)) {
                "Unable to persist the remote video task id"
            }
            task = repository.getTask(id) ?: return Result.success()
            if (task.status == VideoGenerationTaskState.CANCELING) {
                return cancelRemote(task, provider, model)
            }
            return applySnapshot(task, provider, snapshot)
        }

        val remoteTaskId = task.remoteTaskId
            ?: error("Video task ${task.id} has no remote task id")
        val snapshot = providerManager.getVideoGenerationTask(
            setting = provider,
            model = model,
            taskId = remoteTaskId,
        )
        return applySnapshot(task, provider, snapshot)
    }

    private suspend fun applySnapshot(
        task: VideoGenerationTaskEntity,
        provider: ProviderSetting,
        snapshot: VideoGenerationTaskSnapshot,
    ): Result = when (snapshot.status) {
        VideoGenerationTaskStatus.QUEUED,
        VideoGenerationTaskStatus.RUNNING -> {
            val state = if (snapshot.status == VideoGenerationTaskStatus.QUEUED) {
                VideoGenerationTaskState.QUEUED
            } else {
                VideoGenerationTaskState.RUNNING
            }
            val delayMillis = (snapshot.retryAfterMillis ?: DEFAULT_POLL_INTERVAL_MILLIS)
                .coerceIn(MIN_POLL_INTERVAL_MILLIS, MAX_POLL_INTERVAL_MILLIS)
            val transitioned = repository.transition(
                id = task.id,
                target = state,
                progressPercent = snapshot.progressPercent,
                nextPollAt = System.currentTimeMillis() + delayMillis,
            )
            if (transitioned) {
                VideoGenerationWorkScheduler.enqueueNext(workManager, task.id, delayMillis)
            }
            Result.success()
        }

        VideoGenerationTaskStatus.SUCCEEDED -> {
            val existing = repository.getOutputs(task.id).associateBy { it.outputIndex }
            repository.replaceOutputs(
                task.id,
                snapshot.outputs.mapIndexed { index, output ->
                    output.toEntity(task.id, index).copy(
                        localRelativePath = existing[index]?.localRelativePath,
                        sizeBytes = existing[index]?.sizeBytes,
                    )
                },
            )
            check(repository.transition(
                id = task.id,
                target = VideoGenerationTaskState.DOWNLOADING,
                progressPercent = 100,
            )) { "Unable to enter video download state" }
            downloadOutputs(
                task = repository.getTask(task.id) ?: error("Video task disappeared"),
                provider = provider,
            )
        }

        VideoGenerationTaskStatus.FAILED -> {
            val error = requireNotNull(snapshot.error)
            repository.transition(
                id = task.id,
                target = VideoGenerationTaskState.FAILED,
                errorCode = error.code,
                errorMessage = error.message,
                retryable = error.retryable,
            )
            notifyResult(task, false)
            Result.success()
        }

        VideoGenerationTaskStatus.CANCELED -> {
            repository.transition(task.id, VideoGenerationTaskState.CANCELED)
            Result.success()
        }

        VideoGenerationTaskStatus.EXPIRED -> {
            repository.transition(task.id, VideoGenerationTaskState.EXPIRED)
            Result.success()
        }
    }

    private suspend fun cancelRemote(
        task: VideoGenerationTaskEntity,
        provider: ProviderSetting,
        model: Model,
    ): Result {
        val remoteId = task.remoteTaskId
        if (remoteId == null) {
            repository.transition(task.id, VideoGenerationTaskState.FAILED,
                errorCode = "creation_result_unknown",
                errorMessage = "提交期间中断，无法确认远端是否已受理或取消。请先检查供应商任务记录。")
            return Result.success()
        }
        return try {
            providerManager.cancelVideoGenerationTask(provider, model, remoteId)
            repository.transition(task.id, VideoGenerationTaskState.CANCELED)
            Result.success()
        } catch (cancelError: CancellationException) {
            throw cancelError
        } catch (cancelError: Exception) {
            val snapshot = runCatching {
                providerManager.getVideoGenerationTask(provider, model, remoteId)
            }.getOrNull()
            if (snapshot != null && snapshot.status !in setOf(
                    VideoGenerationTaskStatus.QUEUED,
                    VideoGenerationTaskStatus.RUNNING,
                )
            ) {
                applySnapshot(task, provider, snapshot)
            } else {
                throw cancelError
            }
        }
    }

    private suspend fun downloadOutputs(
        task: VideoGenerationTaskEntity,
        provider: ProviderSetting,
    ): Result {
        val outputs = repository.getOutputs(task.id)
        check(outputs.isNotEmpty()) { "Successful video task has no downloadable output" }
        try {
            val notification = me.rerere.rikkahub.service.generationNotification(applicationContext, 1, video = true)
            val info = if (android.os.Build.VERSION.SDK_INT >= 29) {
                androidx.work.ForegroundInfo(task.id.hashCode(), notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else androidx.work.ForegroundInfo(task.id.hashCode(), notification)
            setForeground(info)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            me.rerere.common.android.Logging.logSoftwareError("VideoGenerationWorker", "foreground download unavailable", e)
        }
        outputs.forEach { output ->
            currentCoroutineContext().ensureActive()
            val local = output.localRelativePath?.let { filesManager.getByRelativePath(it) }
            if (local != null && filesManager.getFile(local).let { it.isFile && it.length() == local.sizeBytes }) return@forEach
            require(output.urlExpiresAt == null || output.urlExpiresAt > System.currentTimeMillis()) {
                "Generated video download URL has expired"
            }
            val request = Request.Builder()
                .url(output.remoteUrl)
                .apply {
                    val setting = provider as? ProviderSetting.OpenAI
                    val base = setting?.baseUrl?.toHttpUrl()
                    val target = output.remoteUrl.toHttpUrl()
                    val expected = task.remoteTaskId?.let { id -> base?.newBuilder()?.encodedPath("/v1/videos")
                        ?.addPathSegment(id)?.addPathSegment("content")?.query(null)?.build() }
                    if (base?.host == "sui-xiang.com" && base.isHttps && target == expected) {
                        header("Authorization", "Bearer ${me.rerere.ai.util.KeyRoulette.default().next(setting.apiKey)}")
                    }
                }
                .tag(
                    ProviderRequestDiagnostics::class.java,
                    ProviderRequestDiagnostics(
                        provider = provider.name,
                        model = task.modelApiId,
                        channel = ProviderRequestChannel.COMPATIBLE_ENDPOINT,
                        operation = ProviderRequestOperation.VIDEO_GENERATION_DOWNLOAD,
                        parameters = mapOf(
                            "api" to "ark_video_download",
                            "task_id" to (task.remoteTaskId ?: task.id),
                            "output_index" to output.outputIndex.toString(),
                        ),
                    ),
                )
                .get()
                .build()
            client.newCall(request).awaitAndUse { response ->
                if (!response.isSuccessful) {
                    throw providerRequestFailure(
                        response = response,
                        cause = null,
                        detail = "Failed to download generated video: HTTP ${response.code}",
                    )
                }
                val responseMime = response.body.contentType()?.toString()?.substringBefore(';')
                require(responseMime == null || responseMime.startsWith("video/") || responseMime == "application/octet-stream") {
                    "Download returned a non-video response: $responseMime"
                }
                val mimeType = responseMime?.takeIf { it.startsWith("video/") }
                    ?: output.mimeType.takeIf { it.startsWith("video/") }
                    ?: "video/mp4"
                val expectedSize = response.body.contentLength().takeIf { it > 0L }
                val displayName = safeVideoFileName(
                    output.displayName ?: "seedance-${task.id}-${output.outputIndex}.mp4",
                    mimeType,
                )
                val coroutineContext = currentCoroutineContext()
                val managed = filesManager.saveGeneratedVideoFromStream(
                    input = response.body.byteStream(),
                    displayName = displayName,
                    mimeType = mimeType,
                    expectedSizeBytes = expectedSize,
                    onProgress = { _, _ -> coroutineContext.ensureActive() },
                )
                val persisted = repository.updateDownloadedOutput(
                    taskId = task.id,
                    outputIndex = output.outputIndex,
                    localRelativePath = managed.relativePath,
                    sizeBytes = managed.sizeBytes,
                    mimeType = managed.mimeType,
                    displayName = managed.displayName,
                )
                if (!persisted) {
                    filesManager.delete(managed.id)
                    error("Unable to persist downloaded video output")
                }
            }
        }
        currentCoroutineContext().ensureActive()
        check(repository.transition(task.id, VideoGenerationTaskState.SUCCEEDED, progressPercent = 100)) {
            "Unable to complete video task"
        }
        notifyResult(task, true)
        return Result.success()
    }

    private fun notifyResult(task: VideoGenerationTaskEntity, succeeded: Boolean) {
        val notifications = NotificationManagerCompat.from(applicationContext)
        if (!notifications.areNotificationsEnabled()) return
        runCatching {
            val intent = android.content.Intent(applicationContext, me.rerere.rikkahub.RouteActivity::class.java)
            val pending = android.app.PendingIntent.getActivity(applicationContext, task.id.hashCode(), intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            notifications.notify(task.id.hashCode(), NotificationCompat.Builder(applicationContext, CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.small_icon).setContentTitle(if (succeeded) "视频生成完成" else "视频生成失败")
                .setContentText(task.prompt.take(100)).setContentIntent(pending).setAutoCancel(true).build())
        }
    }

    private suspend fun resolveProviderAndModel(
        task: VideoGenerationTaskEntity,
    ): Pair<ProviderSetting, Model> {
        val settings = settingsStore.settingsFlowRaw.first()
        val provider = settings.providers.firstOrNull { it.id.toString() == task.providerId }
            ?: error("Video provider '${task.providerName}' is no longer configured")
        check(provider.enabled) { "Video provider '${provider.name}' is disabled" }
        val model = provider.models.firstOrNull { it.id.toString() == task.modelId }
            ?: provider.models.firstOrNull { it.modelId == task.modelApiId }
            ?: Model(
                modelId = task.modelApiId,
                displayName = task.modelApiId,
                id = runCatching { Uuid.parse(task.modelId) }.getOrElse { Uuid.random() },
                type = me.rerere.ai.provider.ModelType.VIDEO,
            )
        check(model.modelId == task.modelApiId) { "模型配置已改变，请恢复原模型配置后重试" }
        check(model.providerOverwrite == null) { "视频任务暂不支持模型独立供应商覆盖，请配置独立供应商" }
        return provider to model
    }

    private suspend fun handleFailure(id: String, error: Throwable): Result {
        val task = repository.getTask(id) ?: return Result.failure()
        if (task.status.isTerminal) return Result.success()
        val retryable = error.isRetryableProviderFailure()
        val creationResponseMayBeLost = task.status in setOf(VideoGenerationTaskState.SUBMITTING, VideoGenerationTaskState.CANCELING) &&
            task.remoteTaskId == null
        val shouldRetry = retryable && !creationResponseMayBeLost &&
            task.attemptCount < MAX_TRANSIENT_FAILURES
        if (shouldRetry) {
            val delayMillis = DEFAULT_POLL_INTERVAL_MILLIS
            val transitioned = repository.transition(
                id = id,
                target = task.status,
                errorCode = "temporary_failure",
                errorMessage = error.message ?: "Temporary video task failure",
                retryable = true,
                nextPollAt = System.currentTimeMillis() + delayMillis,
                attemptDelta = 1,
            )
            if (transitioned) {
                VideoGenerationWorkScheduler.enqueueNext(workManager, id, delayMillis)
            }
            return Result.success()
        }
        repository.transition(
            id = id,
            target = VideoGenerationTaskState.FAILED,
            errorCode = if (creationResponseMayBeLost) "creation_result_unknown" else "worker_failure",
            errorMessage = error.message ?: "Video task failed",
            retryable = retryable,
            attemptDelta = 1,
        )
        return Result.success()
    }

    companion object {
        const val KEY_TASK_ID = "video_generation_task_id"
        private const val DEFAULT_POLL_INTERVAL_MILLIS = 10_000L
        private const val MIN_POLL_INTERVAL_MILLIS = 3_000L
        private const val MAX_POLL_INTERVAL_MILLIS = 30_000L
        private const val REMOTE_TASK_RETENTION_MILLIS = 48L * 60L * 60L * 1_000L
        private const val MAX_TRANSIENT_FAILURES = 8
    }
}

private fun VideoGenerationOutput.toEntity(taskId: String, index: Int) =
    VideoGenerationOutputEntity(
        taskId = taskId,
        outputIndex = index,
        remoteUrl = downloadUrl,
        mimeType = mimeType,
        displayName = fileName,
        thumbnailUrl = thumbnailUrl,
        width = width,
        height = height,
        durationMillis = durationMillis,
        urlExpiresAt = downloadUrlExpiresAtEpochMillis,
    )

internal fun safeVideoFileName(name: String, mimeType: String): String {
    val extension = when (mimeType.lowercase()) {
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        else -> "mp4"
    }
    val stem = name.substringBeforeLast('.', name)
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('.', '_')
        .take(80)
        .ifBlank { "generated-video" }
    return "$stem.$extension"
}
