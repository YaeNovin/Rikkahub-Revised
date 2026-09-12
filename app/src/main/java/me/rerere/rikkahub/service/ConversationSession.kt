package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.model.Conversation
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
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()
    val isGenerating: Boolean get() = _generationJob.value?.isActive == true
    val isInUse: Boolean get() = refCount.get() > 0 || isGenerating

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

    fun setJob(job: Job?) {
        val previousJob = _generationJob.value
        previousJob?.cancel()
        _generationJob.value = job
        job?.let(onJobStarted)
        job?.invokeOnCompletion {
            // A replaced job may finish after the new one has already started. Do not let the
            // stale completion callback clear the current generation state.
            if (_generationJob.value === job) {
                _generationJob.value = null
            }
            if (refCount.get() <= 0) {
                scheduleIdleCheck()
            }
        }
    }

    fun getJob(): Job? = _generationJob.value

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
            if (refCount.get() <= 0 && !isGenerating) {
                onIdle(id)
            }
        }
    }

    private fun cancelIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    fun cleanup() {
        _generationJob.value?.cancel()
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
