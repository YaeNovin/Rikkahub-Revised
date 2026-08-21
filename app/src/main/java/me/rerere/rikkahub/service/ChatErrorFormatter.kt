package me.rerere.rikkahub.service

import android.content.Context
import me.rerere.ai.provider.ProviderFailureKind
import me.rerere.ai.provider.ProviderRequestException
import me.rerere.ai.provider.providerFailureKind
import me.rerere.rikkahub.R

internal fun Context.formatChatError(error: Throwable): String {
    val rawMessage = error.primaryDiagnosticMessage()
    return when (error.providerFailureKind()) {
        ProviderFailureKind.RATE_LIMIT -> getString(R.string.error_message_rate_limited)
        ProviderFailureKind.SERVER_TEMPORARY -> getString(R.string.error_message_server_unavailable)
        ProviderFailureKind.NETWORK -> getString(R.string.error_message_network_interrupted)
        ProviderFailureKind.TIMEOUT -> getString(R.string.error_message_timeout)
        ProviderFailureKind.AUTHENTICATION -> getString(R.string.error_message_authentication)
        ProviderFailureKind.REQUEST -> getString(R.string.error_message_request_rejected)
        ProviderFailureKind.UNKNOWN -> when {
            rawMessage.contains("No candidates", ignoreCase = true) ||
                rawMessage.contains("without a final image", ignoreCase = true) ||
                rawMessage.contains("empty response", ignoreCase = true) ->
                getString(R.string.error_message_empty_response)

            rawMessage.contains("maximum number of tokens", ignoreCase = true) ||
                rawMessage.contains("context length", ignoreCase = true) ||
            rawMessage.contains("context window", ignoreCase = true) ->
                getString(R.string.error_message_context_limit)

            else -> formatUserFacingError(rawMessage)
        }
    }
}

internal fun Context.formatUserFacingError(error: Throwable): String = when (error.providerFailureKind()) {
    ProviderFailureKind.RATE_LIMIT -> getString(R.string.error_message_rate_limited)
    ProviderFailureKind.SERVER_TEMPORARY -> getString(R.string.error_message_server_unavailable)
    ProviderFailureKind.NETWORK -> getString(R.string.error_message_network_interrupted)
    ProviderFailureKind.TIMEOUT -> getString(R.string.error_message_timeout)
    ProviderFailureKind.AUTHENTICATION -> getString(R.string.error_message_authentication)
    ProviderFailureKind.REQUEST -> getString(R.string.error_message_request_rejected)
    ProviderFailureKind.UNKNOWN -> formatUserFacingError(error.primaryDiagnosticMessage())
}

internal fun Context.formatUserFacingError(message: String?): String = getString(
    when (classifySoftwareFailure(message.orEmpty())) {
        SoftwareFailureKind.EMPTY_RESPONSE -> R.string.error_message_empty_response
        SoftwareFailureKind.CONTEXT_LIMIT -> R.string.error_message_context_limit
        SoftwareFailureKind.RATE_LIMIT -> R.string.error_message_rate_limited
        SoftwareFailureKind.SERVER_TEMPORARY -> R.string.error_message_server_unavailable
        SoftwareFailureKind.NETWORK -> R.string.error_message_network_interrupted
        SoftwareFailureKind.TIMEOUT -> R.string.error_message_timeout
        SoftwareFailureKind.AUTHENTICATION -> R.string.error_message_authentication
        SoftwareFailureKind.MIDI -> R.string.error_message_midi_processing
        SoftwareFailureKind.DOCUMENT -> R.string.error_message_document_processing
        SoftwareFailureKind.IMAGE -> R.string.error_message_image_processing
        SoftwareFailureKind.FILE_NOT_FOUND -> R.string.error_message_file_not_found
        SoftwareFailureKind.FILE_READ -> R.string.error_message_file_read
        SoftwareFailureKind.FILE_WRITE -> R.string.error_message_file_write
        SoftwareFailureKind.IMPORT -> R.string.error_message_import
        SoftwareFailureKind.EXPORT -> R.string.error_message_export
        SoftwareFailureKind.RESTORE -> R.string.error_message_restore
        SoftwareFailureKind.DELETE -> R.string.error_message_delete
        SoftwareFailureKind.PERMISSION -> R.string.error_message_permission
        SoftwareFailureKind.CONFIGURATION -> R.string.error_message_configuration
        SoftwareFailureKind.INVALID_DATA -> R.string.error_message_invalid_data
        SoftwareFailureKind.UNSUPPORTED -> R.string.error_message_unsupported
        SoftwareFailureKind.TOOL -> R.string.error_message_tool
        SoftwareFailureKind.GENERIC -> R.string.error_message_generic
    }
)

internal enum class SoftwareFailureKind {
    EMPTY_RESPONSE,
    CONTEXT_LIMIT,
    RATE_LIMIT,
    SERVER_TEMPORARY,
    NETWORK,
    TIMEOUT,
    AUTHENTICATION,
    MIDI,
    DOCUMENT,
    IMAGE,
    FILE_NOT_FOUND,
    FILE_READ,
    FILE_WRITE,
    IMPORT,
    EXPORT,
    RESTORE,
    DELETE,
    PERMISSION,
    CONFIGURATION,
    INVALID_DATA,
    UNSUPPORTED,
    TOOL,
    GENERIC,
}

internal fun classifySoftwareFailure(message: String): SoftwareFailureKind {
    val normalized = message.lowercase()
    return when {
        listOf("no candidates", "without a final image", "empty response", "没有返回可用内容")
            .any(normalized::contains) -> SoftwareFailureKind.EMPTY_RESPONSE

        listOf("maximum number of tokens", "context length", "context window", "上下文窗口")
            .any(normalized::contains) -> SoftwareFailureKind.CONTEXT_LIMIT

        listOf(
            "rate limit",
            "too many request",
            "quota exceeded",
            "限流",
            "请求过于频繁",
            "请求过多",
            "频率限制",
            "超过配额",
        )
            .any(normalized::contains) || normalized.hasHttpStatus(429) -> SoftwareFailureKind.RATE_LIMIT

        listOf("timed out", "timeout", "time out", "超时")
            .any(normalized::contains) -> SoftwareFailureKind.TIMEOUT

        listOf(
            "software caused connection abort",
            "stream was reset",
            "stream failed",
            "connection reset",
            "connection abort",
            "connection error",
            "connection failed",
            "failed to connect",
            "broken pipe",
            "unknownhost",
            "websocket",
            "network is unreachable",
            "network error",
            "socketexception",
            "网络连接",
            "连接中断",
            "连接被中止",
            "连接重置",
            "连接失败",
            "流已重置",
            "网络异常",
            "网络不可用",
        ).any(normalized::contains) -> SoftwareFailureKind.NETWORK

        listOf(
            "unauthorized",
            "forbidden",
            "authentication",
            "invalid api key",
            "身份验证",
            "鉴权失败",
            "认证失败",
            "未授权",
            "无效的 api 密钥",
        )
            .any(normalized::contains) || normalized.hasHttpStatus(401, 403) -> SoftwareFailureKind.AUTHENTICATION

        listOf(
            "internal server error",
            "service unavailable",
            "bad gateway",
            "gateway timeout",
            "upstream error",
            "服务不可用",
            "服务暂时不可用",
            "服务器内部错误",
            "服务器错误",
            "网关错误",
            "上游错误",
        )
            .any(normalized::contains) || normalized.hasHttpStatus(500, 502, 503, 504) ->
            SoftwareFailureKind.SERVER_TEMPORARY

        "midi" in normalized || "smpte" in normalized -> SoftwareFailureKind.MIDI
        listOf("docx", "xlsx", "document", "spreadsheet", "word file", "excel file", "文档")
            .any(normalized::contains) -> SoftwareFailureKind.DOCUMENT

        listOf("image", "bitmap", "crop", "qr code", "二维码", "图片")
            .any(normalized::contains) -> SoftwareFailureKind.IMAGE

        listOf("permission", "access denied", "securityexception", "no_permission", "权限", "拒绝访问")
            .any(normalized::contains) -> SoftwareFailureKind.PERMISSION

        listOf("restore", "backup", "恢复", "备份")
            .any(normalized::contains) -> SoftwareFailureKind.RESTORE

        listOf("import", "导入")
            .any(normalized::contains) -> SoftwareFailureKind.IMPORT

        listOf("export", "导出")
            .any(normalized::contains) -> SoftwareFailureKind.EXPORT

        listOf("delete", "remove", "删除", "解散")
            .any(normalized::contains) -> SoftwareFailureKind.DELETE

        listOf("not found", "no such file", "file missing", "找不到文件", "文件不存在")
            .any(normalized::contains) -> SoftwareFailureKind.FILE_NOT_FOUND

        listOf("failed to read", "unable to read", "failed to open", "unable to open", "读取文件失败", "打开文件失败")
            .any(normalized::contains) -> SoftwareFailureKind.FILE_READ

        listOf("failed to save", "unable to save", "failed to write", "unable to write", "failed to finalize", "rename", "保存失败", "写入失败")
            .any(normalized::contains) -> SoftwareFailureKind.FILE_WRITE

        listOf("api key", "configuration", "provider setting", "service account", "配置", "密钥")
            .any(normalized::contains) -> SoftwareFailureKind.CONFIGURATION

        listOf("invalid", "malformed", "parse", "json", "decode", "qr code", "font file", "无效", "解析失败", "格式错误")
            .any(normalized::contains) -> SoftwareFailureKind.INVALID_DATA

        listOf("unsupported", "not supported", "no activity", "unavailable", "不支持", "不可用")
            .any(normalized::contains) -> SoftwareFailureKind.UNSUPPORTED

        listOf("tool", "mcp", "工具")
            .any(normalized::contains) -> SoftwareFailureKind.TOOL

        else -> SoftwareFailureKind.GENERIC
    }
}

private fun String.hasHttpStatus(vararg codes: Int): Boolean = codes.any { code ->
    contains("http $code") ||
        contains("http: $code") ||
        contains("status $code") ||
        contains("status: $code") ||
        contains("status code $code") ||
        contains("status code: $code") ||
        startsWith("$code ")
}

internal fun Throwable.toDiagnosticMessage(): String = stackTraceToString().ifBlank {
    generateSequence(this) { it.cause }
        .joinToString(separator = "\nCaused by: ") { cause ->
            buildString {
                append(cause.javaClass.name)
                cause.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                if (cause is ProviderRequestException) {
                    cause.statusCode?.let { append(" [HTTP ").append(it).append(']') }
                    cause.retryAfterMillis?.let { append(" [Retry-After ").append(it).append("ms]") }
                }
            }
        }
}

private fun Throwable.primaryDiagnosticMessage(): String =
    generateSequence(this) { it.cause }
        .mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
        .firstOrNull()
        ?: javaClass.simpleName
