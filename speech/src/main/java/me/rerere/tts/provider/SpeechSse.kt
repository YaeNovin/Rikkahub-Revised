package me.rerere.tts.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.buffer
import me.rerere.common.http.SseEvent
import me.rerere.common.http.awaitAndUse
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSource

/** Pull-based SSE: a paused audio consumer applies backpressure instead of dropping callback events. */
internal fun OkHttpClient.speechSseFlow(request: Request): Flow<SseEvent> = flow {
    val streamingRequest = request.newBuilder().header("Accept", "text/event-stream").build()
    newCall(streamingRequest).awaitAndUse { response ->
        check(response.isSuccessful) { "语音请求失败：HTTP ${response.code}" }
        check(response.body.contentType()?.let { it.type == "text" && it.subtype == "event-stream" } == true) {
            "语音服务未返回 SSE，请检查模型的流式支持与接口地址。"
        }
        emit(SseEvent.Open)
        readSpeechEvents(response.body.source()).collect { emit(it) }
        emit(SseEvent.Closed)
    }
}.buffer(2).flowOn(Dispatchers.IO)

internal fun readSpeechEvents(source: BufferedSource): Flow<SseEvent.Event> = flow {
    var id: String? = null
    var type: String? = null
    val data = StringBuilder()
    var hasData = false
    var firstLine = true
    suspend fun dispatch() {
        if (hasData) emit(SseEvent.Event(id, type, data.toString().removeSuffix("\n")))
        data.clear(); hasData = false; type = null
    }
    while (true) {
        currentCoroutineContext().ensureActive()
        var line = source.readUtf8Line() ?: break
        if (firstLine) { line = line.removePrefix("\uFEFF"); firstLine = false }
        when {
            line.isEmpty() -> dispatch()
            line.startsWith(":") -> Unit
            else -> {
                val name = line.substringBefore(':')
                val value = if (':' in line) line.substringAfter(':').removePrefix(" ") else ""
                when (name) {
                    "data" -> {
                        check(data.length.toLong() + value.length < 32L * 1024 * 1024) { "语音分片过大。" }
                        data.append(value).append('\n'); hasData = true
                    }
                    "event" -> type = value
                    "id" -> if ('\u0000' !in value) id = value
                }
            }
        }
    }
    dispatch()
}
