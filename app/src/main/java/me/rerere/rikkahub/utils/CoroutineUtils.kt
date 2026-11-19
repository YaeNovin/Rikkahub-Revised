package me.rerere.rikkahub.utils

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import me.rerere.common.android.Logging

private const val TAG = "CoroutineUtils"

fun <T> Flow<T>.toMutableStateFlow(
    scope: CoroutineScope,
    initial: T
): MutableStateFlow<T> {
    val stateFlow = MutableStateFlow(initial)
    scope.launch {
        runCatching {
            this@toMutableStateFlow.collect { value ->
                stateFlow.value = value
            }
        }.onFailure {
            if (it is CancellationException) throw it
            it.printStackTrace()
            Log.e(TAG, "Error while collecting flow: ${it.message}", it)

            // Keep the fail-fast behavior, but let CrashHandler persist the cause.
            // halt() bypasses both it and Android's normal Java crash reporting.
            Logging.logSoftwareError(TAG, "Collect application state", it)
            Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(Thread.currentThread(), it)
                ?: throw it
        }
    }
    return stateFlow
}
