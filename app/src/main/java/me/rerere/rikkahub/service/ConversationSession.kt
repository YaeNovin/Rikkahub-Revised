package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.PromptInjectionDiagnostics
import me.rerere.rikkahub.data.memory.MemoryExtractionStatus
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.uuid.Uuid

private const val TAG = "ConversationSession"
private const val IDLE_TIMEOUT_MS = 5_000L

class ConversationSession(
    val id: Uuid,
    initial: Conversation,
    private val scope: CoroutineScope,
    private val onIdle: (Uuid) -> Unit,
    private val onJobStarted: (Job) -> Unit = {},
) {
    // 会话状态
    val state = MutableStateFlow(initial)
    private val pendingInput = MutableStateFlow<List<UIMessage>>(emptyList())
    val pendingUserMessages: StateFlow<List<UIMessage>> = pendingInput.asStateFlow()

    fun acceptUserMessage(message: UIMessage) {
        pendingInput.update { it + message }
    }

    fun finishUserMessageCommit(messageId: Uuid) {
        pendingInput.update { list -> list.filterNot { it.id == messageId } }
    }

    private val initializationMutex = Mutex()
    private val persistenceMutex = Mutex()

    @Volatile
    private var initialized = false
    val isInitialized: Boolean get() = initialized

    // 原子引用计数
    private val refCount = AtomicInteger(0)

    // 处理状态（如 OCR 识别中）
    val processingStatus = MutableStateFlow<String?>(null)

    val promptInjectionDiagnostics = MutableStateFlow<PromptInjectionDiagnostics?>(null)

    val memoryExtractionStatus = MutableStateFlow<MemoryExtractionStatus>(MemoryExtractionStatus.Idle)
    private val memoryExtractionScheduleVersion = AtomicLong(0L)
    private val titleGenerationVersion = AtomicLong(0L)
    internal val suggestionGeneration = SuggestionGenerationGate()

    // 生成任务（内聚在 session 中）
    private val _generationJob = MutableStateFlow<Job?>(null)
    @Volatile private var currentJob: Job? = null
    @Volatile private var acceptedCommit: Deferred<Boolean>? = null
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()
    val isGenerating: Boolean get() = _generationJob.value?.isActive == true
    val isInUse: Boolean get() = refCount.get() > 0 || currentJob?.isCompleted == false || acceptedCommit?.isCompleted == false || pendingInput.value.isNotEmpty()

    // 空闲检查任务
    private var idleCheckJob: Job? = null

    fun acquire(): Int = refCount.incrementAndGet().also {
        cancelIdleCheck()
        Log.d(TAG, "acquire $id (refs=$it)")
    }

    fun release(): Int = refCount.decrementAndGet().also {
        Log.d(TAG, "release $id (refs=$it)")
        if (it <= 0) scheduleIdleCheck()
    }

    // 作用域 API - 短请求（REST）
    inline fun <T> withRef(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    // 作用域 API - 长连接（SSE、挂起函数）
    suspend inline fun <T> withRefSuspend(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    @Synchronized fun setJob(job: Job?) {
        val previousJob = currentJob
        previousJob?.cancel()
        currentJob = job
        _generationJob.value = job
        job?.let(onJobStarted)
        job?.invokeOnCompletion {
            // A replaced job may finish after the new one has already started. Do not let the
            // stale completion callback clear the current generation state.
            synchronized(this) {
                if (currentJob === job) {
                    currentJob = null
                    _generationJob.value = null
                    processingStatus.value = null
                }
                if (refCount.get() <= 0) {
                    scheduleIdleCheck()
                }
            }
        }
    }

    @Synchronized fun getJob(): Job? = currentJob

    /** Keep the completion barrier, but acknowledge Stop immediately in the observable UI state. */
    @Synchronized fun cancelGeneration(): Job? {
        val job = currentJob ?: return null
        _generationJob.value = null
        processingStatus.value = null
        job.cancel()
        return job
    }

    /** Accepted input belongs to the conversation even when its answer is cancelled before launch. */
    @Synchronized internal fun launchAcceptedTurn(
        commit: suspend () -> Unit,
        generate: suspend () -> Unit,
        onError: (Exception) -> Unit,
    ): Job {
        val previous = currentJob
        val previousCommit = acceptedCommit
        previous?.cancel()
        val committed = scope.async(Dispatchers.IO) {
            try {
                previousCommit?.await()
                previous?.join()
                commit()
                true
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                onError(error)
                false
            }
        }
        acceptedCommit = committed
        committed.invokeOnCompletion {
            synchronized(this) { if (refCount.get() <= 0) scheduleIdleCheck() }
        }
        val job = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            try {
                if (committed.await()) generate()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onError(error)
            }
        }
        setJob(job)
        job.start()
        return job
    }

    fun nextMemoryExtractionSchedule(): Long = memoryExtractionScheduleVersion.incrementAndGet()

    fun isCurrentMemoryExtractionSchedule(version: Long): Boolean =
        memoryExtractionScheduleVersion.get() == version

    fun nextTitleGeneration(): Long = titleGenerationVersion.incrementAndGet()

    fun isCurrentTitleGeneration(version: Long): Boolean =
        titleGenerationVersion.get() == version

    fun nextSuggestionGeneration(): Long = suggestionGeneration.invalidate()

    fun isCurrentSuggestionGeneration(version: Long): Boolean =
        suggestionGeneration.isCurrent(version)

    suspend fun initializeOnce(initializer: suspend () -> Conversation): Conversation =
        initializationMutex.withLock {
            if (!initialized) {
                val loaded = initializer()
                // A send may publish newer in-memory state while the database load suspends.
                if (!initialized) {
                    state.value = loaded
                    initialized = true
                }
            }
            state.value
        }

    fun markInitialized() {
        initialized = true
    }

    suspend fun <T> withPersistenceLock(block: suspend () -> T): T =
        persistenceMutex.withLock { block() }

    private fun scheduleIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (!isInUse) {
                onIdle(id)
            }
        }
    }

    private fun cancelIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    fun cleanup() {
        currentJob?.cancel()
        acceptedCommit?.cancel()
        currentJob = null
        _generationJob.value = null
        idleCheckJob?.cancel()
        idleCheckJob = null
        promptInjectionDiagnostics.value = null
        memoryExtractionStatus.value = MemoryExtractionStatus.Idle
        memoryExtractionScheduleVersion.incrementAndGet()
        titleGenerationVersion.incrementAndGet()
        suggestionGeneration.invalidate()
    }
}

/** Stable IDs avoid duplicate rows while the durable snapshot catches up with accepted input. */
internal fun Conversation.withPendingUserMessages(pending: List<UIMessage>): Conversation {
    if (pending.isEmpty()) return this
    val existingIds = messageNodes.flatMapTo(hashSetOf()) { node -> node.messages.map { it.id } }
    val additions = pending.filterNot { it.id in existingIds }
    return if (additions.isEmpty()) this else copy(messageNodes = messageNodes + additions.map { it.toMessageNode() })
}
