package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ProviderRequestDiagnostics
import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import me.rerere.common.android.extractErrorReason
import me.rerere.rikkahub.utils.JsonInstantPretty
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer

private const val MAX_LOGGED_REQUEST_BODY_BYTES = 64L * 1024L
private const val MAX_LOGGED_ERROR_BODY_BYTES = 16L * 1024L
private const val MAX_LOGGED_ERROR_REASON_CHARS = 2_048
private const val REDACTED = "[REDACTED]"

class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val diagnostics = request.tag(ProviderRequestDiagnostics::class.java)
        val recordHttpRequest = Logging.isRequestLoggingEnabled()
        if (!recordHttpRequest && diagnostics == null) {
            return chain.proceed(request)
        }

        val startTime = System.currentTimeMillis()
        val requestHeaders = if (recordHttpRequest) request.headers.toSafeMap() else emptyMap()
        val requestBody = if (recordHttpRequest) request.body.readSanitizedBody() else null

        val response: Response
        var error: String? = null

        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            error = sanitizeErrorMessage(e.message)
            val durationMs = System.currentTimeMillis() - startTime
            buildUnifiedRequestLog(
                diagnostics = diagnostics,
                recordHttpRequest = recordHttpRequest,
                url = request.url.toSafeLogUrl().takeIf { recordHttpRequest },
                method = request.method.takeIf { recordHttpRequest },
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                durationMs = durationMs,
                error = error,
            )?.record()
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime
        if (!response.isSuccessful) {
            error = response.readSafeErrorReason()
        }
        buildUnifiedRequestLog(
            diagnostics = diagnostics,
            recordHttpRequest = recordHttpRequest,
            url = request.url.toSafeLogUrl().takeIf { recordHttpRequest },
            method = request.method.takeIf { recordHttpRequest },
            requestHeaders = requestHeaders,
            requestBody = requestBody,
            responseCode = response.code,
            responseHeaders = if (recordHttpRequest) response.headers.toSafeMap() else emptyMap(),
            durationMs = durationMs,
            error = error,
        )?.record()

        return response
    }
}

private fun Response.readSafeErrorReason(): String {
    val responseBody = runCatching {
        peekBody(MAX_LOGGED_ERROR_BODY_BYTES).string()
    }.getOrNull()
    return extractLoggedResponseError(responseBody)
        ?: "HTTP $code ${message.takeIf(String::isNotBlank).orEmpty()}".trim()
}

internal fun extractLoggedResponseError(body: String?): String? {
    val normalized = body?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val reason = extractErrorReason(normalized) ?: normalized.lineSequence()
        .map(String::trim)
        .firstOrNull(String::isNotEmpty)
        ?: return null
    return sanitizeErrorMessage(reason)
        ?.replace(Regex("[\\r\\n\\t]+"), " ")
        ?.replace(Regex(" {2,}"), " ")
        ?.take(MAX_LOGGED_ERROR_REASON_CHARS)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
}

internal fun buildUnifiedRequestLog(
    diagnostics: ProviderRequestDiagnostics?,
    recordHttpRequest: Boolean,
    url: String? = null,
    method: String? = null,
    requestHeaders: Map<String, String> = emptyMap(),
    requestBody: String? = null,
    responseCode: Int? = null,
    responseHeaders: Map<String, String> = emptyMap(),
    durationMs: Long? = null,
    error: String? = null,
): LogEntry? = when {
    diagnostics != null ->
            LogEntry.ProviderRequestLog(
                provider = diagnostics.provider,
                model = diagnostics.model,
                channel = diagnostics.channel.name,
                operation = diagnostics.operation.name,
                parameters = diagnostics.parameters,
                responseCode = responseCode,
                durationMs = durationMs,
                error = error,
                url = url.takeIf { recordHttpRequest },
                method = method.takeIf { recordHttpRequest },
                requestHeaders = requestHeaders.takeIf { recordHttpRequest }.orEmpty(),
                requestBody = requestBody.takeIf { recordHttpRequest },
                responseHeaders = responseHeaders.takeIf { recordHttpRequest }.orEmpty(),
            )

    recordHttpRequest -> LogEntry.RequestLog(
        tag = "HTTP",
        url = requireNotNull(url),
        method = requireNotNull(method),
        requestHeaders = requestHeaders,
        requestBody = requestBody,
        responseCode = responseCode,
        responseHeaders = responseHeaders,
        durationMs = durationMs,
        error = error,
    )

    else -> null
}

private fun LogEntry.record() {
    when (this) {
        is LogEntry.ProviderRequestLog -> Logging.logProviderRequest(this)
        is LogEntry.RequestLog -> Logging.logRequest(this)
        is LogEntry.ErrorLog,
        is LogEntry.TextLog -> Unit
    }
}

internal fun Headers.toSafeMap(): Map<String, String> = names().associateWith { name ->
    if (name.lowercase() in SAFE_HEADER_FIELDS) get(name).orEmpty() else REDACTED
}

internal fun HttpUrl.toSafeLogUrl(): String {
    if (queryParameterNames.none(String::isSensitiveLogField)) return toString()
    return newBuilder().apply {
        queryParameterNames
            .filter(String::isSensitiveLogField)
            .forEach { name -> setQueryParameter(name, REDACTED) }
    }.build().toString()
}

private fun RequestBody?.readSanitizedBody(): String? {
    if (this == null) return null
    val length = runCatching { contentLength() }.getOrDefault(-1L)
    if (length < 0L) return "[request body omitted: unknown length]"
    if (length > MAX_LOGGED_REQUEST_BODY_BYTES) return "[request body omitted: $length bytes]"
    return runCatching {
        val buffer = Buffer()
        writeTo(buffer)
        sanitizeRequestBody(buffer.readUtf8())
    }.getOrElse { "[request body omitted: unreadable]" }
}

internal fun sanitizeRequestBody(body: String): String {
    if (body.isBlank()) return body
    val parsed = runCatching { JsonInstantPretty.parseToJsonElement(body) }.getOrNull()
        ?: return "[request body omitted: ${body.length} characters]"
    return sanitizeJsonForLog(parsed).toString()
}

private fun sanitizeJsonForLog(element: JsonElement, fieldName: String? = null): JsonElement {
    if (fieldName?.isSensitiveLogField() == true) return JsonPrimitive(REDACTED)
    if (fieldName != null && fieldName.lowercase() in SCHEMA_FIELDS) {
        return JsonPrimitive("[SCHEMA omitted]")
    }
    if (fieldName != null && fieldName.lowercase() in TEXT_FIELDS && element is JsonPrimitive) {
        val length = element.contentOrNull?.length ?: 0
        return JsonPrimitive("[TEXT omitted: $length characters]")
    }
    if (fieldName != null && fieldName.lowercase() in BINARY_FIELDS && element is JsonPrimitive) {
        val length = element.contentOrNull?.length ?: 0
        return JsonPrimitive("[BINARY omitted: $length characters]")
    }
    if (fieldName != null && fieldName.lowercase() in SEQUENCE_FIELDS) {
        return when (element) {
            is JsonArray -> JsonPrimitive("[${element.size} sequences omitted]")
            else -> JsonPrimitive("[SEQUENCE omitted]")
        }
    }
    if (fieldName != null && fieldName.lowercase() in URI_FIELDS && element is JsonPrimitive) {
        return JsonPrimitive("[URI omitted]")
    }

    return when (element) {
        is JsonObject -> buildJsonObject {
            element.forEach { (key, value) -> put(key, sanitizeJsonForLog(value, key)) }
        }
        is JsonArray -> buildJsonArray {
            element.forEach { add(sanitizeJsonForLog(it, fieldName)) }
        }
        is JsonPrimitive -> if (
            element.isString && fieldName?.lowercase() !in SAFE_STRING_FIELDS
        ) {
            JsonPrimitive("[STRING omitted: ${element.contentOrNull?.length ?: 0} characters]")
        } else {
            element
        }
        JsonNull -> element
    }
}

private fun String.isSensitiveLogField(): Boolean = lowercase()
    .replace("-", "")
    .replace("_", "") in SENSITIVE_FIELDS

private fun sanitizeErrorMessage(message: String?): String? = message
    ?.replace(
        Regex("(?i)((?:[?&]|\\b)(?:key|api[_-]?key|access[_-]?token|token)=)[^&\\s]+"),
        "$1$REDACTED",
    )
    ?.replace(Regex("(?i)(bearer\\s+)[a-z0-9._~+/-]+"), "$1$REDACTED")
    ?.replace(
        Regex("(?i)([\\\"']?(?:api[_-]?key|access[_-]?token|refresh[_-]?token)[\\\"']?\\s*[:=]\\s*[\\\"'])[^\\\"']+"),
        "$1$REDACTED",
    )

private val SENSITIVE_FIELDS = setOf(
    "authorization",
    "proxyauthorization",
    "xgoogapikey",
    "apikey",
    "key",
    "accesstoken",
    "refreshtoken",
    "token",
    "cookie",
    "setcookie",
)
private val TEXT_FIELDS = setOf("text", "prompt", "input", "instructions", "content")
private val BINARY_FIELDS = setOf("data", "base64", "bytes")
private val SEQUENCE_FIELDS = setOf("stopsequences", "stop_sequences")
private val SCHEMA_FIELDS = setOf("responsejsonschema", "response_json_schema", "schema")
private val URI_FIELDS = setOf(
    "fileuri",
    "file_uri",
    "url",
    "uri",
    "imageurl",
    "image_url",
    "audiourl",
    "audio_url",
    "videourl",
    "video_url",
)
private val SAFE_HEADER_FIELDS = setOf(
    "accept",
    "accept-encoding",
    "content-encoding",
    "content-length",
    "content-type",
    "date",
    "server",
    "user-agent",
    "x-request-id",
)
private val SAFE_STRING_FIELDS = setOf(
    "model",
    "role",
    "type",
    "mimetype",
    "mime_type",
    "responsemimetype",
    "response_mime_type",
    "thinkinglevel",
    "thinking_level",
    "threshold",
    "category",
    "mediaresolution",
    "media_resolution",
    "level",
    "aspectratio",
    "aspect_ratio",
    "imagesize",
    "image_size",
    "quality",
    "size",
    "background",
    "outputformat",
    "output_format",
    "reasoningeffort",
    "reasoning_effort",
)
