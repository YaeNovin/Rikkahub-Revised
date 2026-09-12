package me.rerere.rikkahub.data.github

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.db.dao.GitHubRepositoryCacheDAO
import me.rerere.rikkahub.data.db.entity.GitHubRepositoryCacheEntity
import me.rerere.rikkahub.data.db.entity.GitHubApiCacheStateEntity
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl

@Serializable
data class GitHubRepositoryData(
    val fullName: String,
    val description: String?,
    val language: String?,
    val stars: Long,
    val forks: Long,
    val license: String?,
    val archived: Boolean,
    val fork: Boolean,
)

enum class GitHubCardStatus { FRESH, STALE, UNAVAILABLE, RATE_LIMITED }
enum class GitHubCardFailure { NETWORK, DNS, TIMEOUT, TLS, HTTP, INVALID_RESPONSE }
data class GitHubCardResult(
    val data: GitHubRepositoryData?, val status: GitHubCardStatus,
    val failure: GitHubCardFailure? = null, val httpStatus: Int? = null, val retryAt: Long? = null,
)

/** One request queue for all cards, with fresh/negative caches rechecked inside the lock. */
class GitHubRepository(
    private val dao: GitHubRepositoryCacheDAO,
    private val client: Call.Factory = publicGitHubClient(),
    private val onFailure: (String, GitHubCardResult) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val requests = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    fun observe(fullName: String) = dao.observe(requireNotNull(GitHubRepositoryUrl.canonicalFullName(fullName)))
        .map { it.result(clock()) }.flowOn(Dispatchers.IO)

    suspend fun cached(fullName: String): GitHubCardResult = withContext(Dispatchers.IO) {
        val key = requireNotNull(GitHubRepositoryUrl.canonicalFullName(fullName))
        dao.get(key).result(clock())
    }

    suspend fun get(fullName: String, forceRefresh: Boolean = false): GitHubCardResult = withContext(Dispatchers.IO) {
        val key = requireNotNull(GitHubRepositoryUrl.canonicalFullName(fullName))
        requests.withLock {
            val now = clock()
            val cached = dao.get(key)
            val data = cached.decode()
            val manualRetry = forceRefresh && cached?.error != null && now >= cached.lastAccessAt + MANUAL_RETRY_DELAY
            if (!manualRetry && cached != null && now < cached.refreshAfter && (data != null || cached.error != null)) {
                if (cached.error == null) dao.touch(key, now)
                return@withLock cached.result(now)
            }
            var apiState = dao.apiState() ?: GitHubApiCacheStateEntity()
            if (now < apiState.blockedUntil) return@withLock GitHubCardResult(data, GitHubCardStatus.RATE_LIMITED, retryAt = apiState.blockedUntil)
            if (now < apiState.windowStartedAt || now - apiState.windowStartedAt >= HOUR) {
                apiState = apiState.copy(windowStartedAt = now, requests = 0)
            }
            if (apiState.requests >= LOCAL_HOURLY_BUDGET) {
                dao.putApiState(apiState.copy(blockedUntil = apiState.windowStartedAt + HOUR))
                return@withLock GitHubCardResult(data, GitHubCardStatus.RATE_LIMITED, retryAt = apiState.windowStartedAt + HOUR)
            }
            // Reserve before dispatch so cancellation/process restart cannot reset the budget.
            apiState = apiState.copy(requests = apiState.requests + 1)
            dao.putApiState(apiState)
            val request = Request.Builder()
                .url("https://api.github.com".toHttpUrl().newBuilder().addPathSegment("repos")
                    .addPathSegment(key.substringBefore('/')).addPathSegment(key.substringAfter('/')).build())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2026-03-10")
                .header("User-Agent", "RikkaHub-GitHubCards")
                .apply { if (data != null) cached?.etag?.let { header("If-None-Match", it) } }
                .build()
            try {
                client.repositoryResponse(request) {
                    if (apiState.requests >= LOCAL_HOURLY_BUDGET) false else {
                        apiState = apiState.copy(requests = apiState.requests + 1)
                        dao.putApiState(apiState)
                        true
                    }
                }.use { response ->
                    val receivedAt = clock()
                    val errorBody = if (response.code == 403) response.peekBody(4096).string() else null
                    val retryAt = githubRetryAt(response.code, response.headers, receivedAt, errorBody)
                    if (retryAt != null) dao.putApiState(apiState.copy(blockedUntil = retryAt))
                    val updated = when {
                        response.code == 200 -> {
                            val body = response.body
                            if (body.contentLength() > MAX_RESPONSE_BYTES) throw InvalidRepositoryResponse()
                            val source = body.source()
                            source.request(MAX_RESPONSE_BYTES + 1)
                            if (source.buffer.size > MAX_RESPONSE_BYTES) throw InvalidRepositoryResponse()
                            val parsed = try { parseGitHubRepositoryData(source.readUtf8()) }
                                catch (_: Exception) { throw InvalidRepositoryResponse() }
                            GitHubRepositoryCacheEntity(key, json.encodeToString(parsed), response.header("ETag"),
                                receivedAt, receivedAt + FRESH_TTL, receivedAt, null)
                        }
                        response.code == 304 && data != null && cached != null ->
                            cached.copy(fetchedAt = receivedAt, refreshAfter = receivedAt + FRESH_TTL,
                                lastAccessAt = receivedAt, etag = response.header("ETag") ?: cached.etag, error = null)
                        else -> GitHubRepositoryCacheEntity(key,
                            // A 404/410 can mean deleted/newly private; do not expose stale metadata.
                            cached?.payload.takeUnless { response.code in setOf(404, 410) }, cached?.etag,
                            cached?.fetchedAt ?: 0, retryAt ?: receivedAt + if (response.code in setOf(404,410)) FRESH_TTL else FAILURE_TTL,
                            receivedAt, if (retryAt != null && response.code in setOf(403, 429)) "rate_limited" else "http:${response.code}")
                    }
                    dao.put(updated)
                    dao.trim()
                    updated.result(receivedAt).also { if (updated.error != null) reportFailure(key, it) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                val failed = GitHubRepositoryCacheEntity(key, cached?.payload, cached?.etag,
                    cached?.fetchedAt ?: 0, clock() + FAILURE_TTL, clock(), when (error) {
                        is java.net.UnknownHostException -> "dns"
                        is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "timeout"
                        is javax.net.ssl.SSLException -> "tls"
                        is InvalidRepositoryResponse -> "invalid_response"
                        else -> "network"
                    })
                dao.put(failed)
                dao.trim()
                failed.result(clock()).also { reportFailure(key, it) }
            }
        }
    }

    private fun reportFailure(key: String, result: GitHubCardResult) {
        // Diagnostic persistence must not replace the original network result.
        runCatching { onFailure(key, result) }
    }

    private fun GitHubRepositoryCacheEntity?.decode(): GitHubRepositoryData? =
        this?.payload?.let { runCatching { json.decodeFromString<GitHubRepositoryData>(it) }.getOrNull() }

    private fun GitHubRepositoryCacheEntity?.result(now: Long): GitHubCardResult {
        val data = decode()
        return GitHubCardResult(data, when {
            this?.error == "rate_limited" && now < refreshAfter -> GitHubCardStatus.RATE_LIMITED
            this == null || data == null -> GitHubCardStatus.UNAVAILABLE
            error != null || now >= refreshAfter -> GitHubCardStatus.STALE
            else -> GitHubCardStatus.FRESH
        }, failure = when {
            this?.error?.startsWith("http:") == true -> GitHubCardFailure.HTTP
            this?.error == "dns" -> GitHubCardFailure.DNS
            this?.error == "timeout" -> GitHubCardFailure.TIMEOUT
            this?.error == "tls" -> GitHubCardFailure.TLS
            this?.error == "invalid_response" -> GitHubCardFailure.INVALID_RESPONSE
            this?.error != null && error != "rate_limited" -> GitHubCardFailure.NETWORK
            else -> null
        }, httpStatus = this?.error?.removePrefix("http:")?.toIntOrNull(),
            retryAt = if (this?.error == "rate_limited") refreshAfter else if (this?.error != null) lastAccessAt + MANUAL_RETRY_DELAY else null)
    }

    companion object {
        internal const val FRESH_TTL = 30 * 60 * 1000L
        internal const val FAILURE_TTL = 5 * 60 * 1000L
        internal const val HOUR = 60 * 60 * 1000L
        internal const val LOCAL_HOURLY_BUDGET = 50
        private const val MANUAL_RETRY_DELAY = 5_000L
        private const val MAX_RESPONSE_BYTES = 512 * 1024L
    }
}

internal fun parseGitHubRepositoryData(source: String): GitHubRepositoryData {
    val value = Json.parseToJsonElement(source).jsonObject
    fun text(key: String) = (value[key] as? JsonPrimitive)?.contentOrNull
    fun count(key: String) = (value[key] as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0) ?: 0
    val fullName = requireNotNull(text("full_name"))
    require(GitHubRepositoryUrl.canonicalFullName(fullName) != null)
    require((value["private"] as? JsonPrimitive)?.booleanOrNull == false)
    return GitHubRepositoryData(fullName, text("description")?.take(500), text("language")?.take(80),
        count("stargazers_count"), count("forks_count"),
        (value["license"] as? JsonObject)?.get("spdx_id")?.jsonPrimitive?.contentOrNull?.takeUnless { it == "NOASSERTION" },
        (value["archived"] as? JsonPrimitive)?.booleanOrNull ?: false,
        (value["fork"] as? JsonPrimitive)?.booleanOrNull ?: false)
}

internal fun githubRetryAt(status: Int, headers: Headers, now: Long, errorBody: String? = null): Long? {
    val throttled = status == 429 || headers["X-RateLimit-Remaining"] == "0" ||
        (status == 403 && (headers["Retry-After"] != null || errorBody.orEmpty().contains("rate limit", ignoreCase = true)))
    if (!throttled) return null
    val reset = headers["X-RateLimit-Reset"]?.toLongOrNull()?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000)
    val retry = headers["Retry-After"]?.let { value ->
        value.toLongOrNull()?.takeIf { it in 0..86400 }?.let { now + it * 1000 }
            ?: runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
    }
    return maxOf(now + 60_000, retry ?: 0, reset?.takeIf { headers["X-RateLimit-Remaining"] == "0" } ?: 0)
}

private fun publicGitHubClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(true).build()

private class InvalidRepositoryResponse : IOException("Invalid GitHub repository metadata")

internal fun safeRepositoryRedirect(from: HttpUrl, location: String?): HttpUrl? {
    val target = location?.let(from::resolve) ?: return null
    if (target.scheme != "https" || target.host != "api.github.com" || target.port != 443 ||
        target.username.isNotEmpty() || target.password.isNotEmpty() || target.query != null || target.fragment != null) return null
    val path = target.encodedPath
    return target.takeIf { Regex("/repositories/[0-9]+").matches(path) ||
        (path.startsWith("/repos/") && GitHubRepositoryUrl.canonicalFullName(path.removePrefix("/repos/")) != null) }
}

private suspend fun Call.Factory.repositoryResponse(initial: Request, reserve: suspend () -> Boolean): Response {
    var request = initial
    repeat(3) { hop ->
        val response = newCall(request).awaitResponse()
        if (response.code !in setOf(301, 302, 307, 308) || hop == 2) return response
        val target = safeRepositoryRedirect(request.url, response.header("Location")) ?: return response
        val allowed = try { reserve() } catch (error: Throwable) { response.close(); throw error }
        if (!allowed) return response
        response.close()
        request = request.newBuilder().url(target).removeHeader("If-None-Match").build()
    }
    error("Unreachable redirect state")
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
