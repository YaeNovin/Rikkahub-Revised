package me.rerere.rikkahub.service

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SuggestionGenerationState { IDLE, GENERATING, FAILED }

/** One request per conversation; invalidation also cancels the obsolete network request. */
internal class SuggestionGenerationGate {
    private var version = 0L
    private var job: Job? = null
    private val mutableState = MutableStateFlow(SuggestionGenerationState.IDLE)
    val state = mutableState.asStateFlow()

    @Synchronized fun begin(requestJob: Job): Long? {
        if (job?.isActive == true) return null
        job = requestJob
        mutableState.value = SuggestionGenerationState.GENERATING
        return ++version
    }

    @Synchronized fun invalidate(): Long {
        ++version
        job?.cancel()
        job = null
        mutableState.value = SuggestionGenerationState.IDLE
        return version
    }

    @Synchronized fun isCurrent(token: Long) = token == version

    @Synchronized fun finish(token: Long, failed: Boolean) {
        if (token != version) return
        job = null
        mutableState.value = if (failed) SuggestionGenerationState.FAILED else SuggestionGenerationState.IDLE
    }
}
