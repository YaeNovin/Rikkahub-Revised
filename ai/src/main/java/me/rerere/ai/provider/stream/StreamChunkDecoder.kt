package me.rerere.ai.provider.stream

import java.io.EOFException
import me.rerere.ai.ui.StreamChunk

/**
 * 一次 Provider 响应流所使用的有状态、传输无关解码器。
 * 每条响应流必须创建独立实例。
 */
interface StreamChunkDecoder {
    /** 将单个原始 SSE 事件转换为通用流事件。解析失败时直接抛出异常。 */
    fun accept(event: SseEvent): DecodeResult

    /** SSE 关闭时收尾；缺少协议终止事件时应抛出可重试的提前结束异常。 */
    fun onClosed(): List<StreamChunk>
}

internal fun prematureStreamTermination(protocol: String): Nothing {
    throw EOFException("$protocol stream ended before a terminal event")
}

data class DecodeResult(
    val chunks: List<StreamChunk> = emptyList(),
    /** Provider 协议已经明确结束，传输层可以主动关闭连接。 */
    val completed: Boolean = false,
)
