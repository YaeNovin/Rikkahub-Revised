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
import kotlinx.coroutines.flow.*
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
    val ownerAvatarUrl: String? = null,
    val isPrivate: Boolean = false,
    val openIssuesAndPullRequests: Long? = null,
    val languageBytes: Map<String, Long> = emptyMap(),
    val languageBreakdownComplete: Boolean = false,
    val languageBreakdownAttemptedAt: Long = 0,
)

enum class GitHubCardStatus { FRESH, STALE, UNAVAILABLE, RATE_LIMITED }
enum class GitHubCardFailure { NETWORK, DNS, TIMEOUT, TLS, HTTP, INVALID_RESPONSE, AUTHENTICATION, PERMISSION, CREDENTIALS_UNAVAILABLE }
enum class GitHubRateLimitKind { LOCAL, PRIMARY, SECONDARY, SERVER }
data class GitHubCardResult(
    val data: GitHubRepositoryData?, val status: GitHubCardStatus,
    val failure: GitHubCardFailure? = null, val httpStatus: Int? = null, val retryAt: Long? = null,
    val rateLimitKind: GitHubRateLimitKind? = null,
)

/** One request queue for all cards, with fresh/negative caches rechecked inside the lock. */
class GitHubRepository(
    private val dao: GitHubRepositoryCacheDAO,
    private val client: Call.Factory = publicGitHubClient(),
    private val onFailure: (String, GitHubCardResult) -> Unit = { _, _ -> },
    private val credentials: GitHubCredentialSource = AnonymousGitHubCredentials,
    private val budgets: () -> GitHubRequestBudgets = { GitHubRequestBudgets() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val requests = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val privateRows = MutableStateFlow<Map<String, GitHubRepositoryCacheEntity>>(emptyMap())
    private val invalidScopes = MutableStateFlow<Set<String>>(emptySet())
    private val mutableQuota = MutableStateFlow<GitHubApiQuota?>(null)
    val quota: StateFlow<GitHubApiQuota?> = mutableQuota
    val credentialState: StateFlow<GitHubTokenState> = credentials.state
    private var activeRevision = -1L
    private val credentialLock = Any()

    private suspend fun auth(): GitHubCredentials = credentials.read().also { auth ->
        synchronized(credentialLock) {
            if (activeRevision != auth.revision) {
                activeRevision = auth.revision
                privateRows.value = emptyMap()
                mutableQuota.value = null
            }
        }
    }
    private fun cacheKey(key: String, auth: GitHubCredentials) = if (auth.token == null) key else "${auth.scope}:$key"
    private suspend fun row(key: String, auth: GitHubCredentials) =
        privateRows.value[cacheKey(key, auth)] ?: dao.get(cacheKey(key, auth))
    private fun current(auth: GitHubCredentials) = credentials.state.value.revision == auth.revision
    private suspend fun store(entity: GitHubRepositoryCacheEntity, auth: GitHubCredentials, memoryOnly: Boolean): Boolean {
        if (!current(auth)) return false
        if (memoryOnly) {
            dao.remove(entity.fullName)
            if (!current(auth)) return false
            privateRows.update { (it + (entity.fullName to entity)).entries.toList().takeLast(50).associate { entry -> entry.toPair() } }
        } else {
            privateRows.update { it - entity.fullName }
            dao.put(entity)
        }
        if (!current(auth)) {
            privateRows.update { it - entity.fullName }
            return false
        }
        return true
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(fullName: String): Flow<GitHubCardResult> = credentials.state.flatMapLatest {
        flow {
            val auth = auth()
            val key = cacheKey(requireNotNull(GitHubRepositoryUrl.canonicalFullName(fullName)), auth)
            emitAll(combine(dao.observe(key), privateRows, invalidScopes) { cached, memory, invalid ->
                when {
                    auth.unreadable -> GitHubCardResult(null, GitHubCardStatus.UNAVAILABLE, GitHubCardFailure.CREDENTIALS_UNAVAILABLE)
                    auth.scope in invalid -> GitHubCardResult(null, GitHubCardStatus.UNAVAILABLE, GitHubCardFailure.AUTHENTICATION, httpStatus = 401)
                    else -> (memory[key] ?: cached).result(clock())
                }
            })
        }
    }.flowOn(Dispatchers.IO)

    suspend fun cached(fullName: String): GitHubCardResult = withContext(Dispatchers.IO) {
        val key = requireNotNull(GitHubRepositoryUrl.canonicalFullName(fullName))
        val auth = auth()
        if (auth.unreadable) GitHubCardResult(null, GitHubCardStatus.UNAVAILABLE, GitHubCardFailure.CREDENTIALS_UNAVAILABLE)
        else if (auth.scope in invalidScopes.value) GitHubCardResult(null, GitHubCardStatus.UNAVAILABLE, GitHubCardFailure.AUTHENTICATION, httpStatus = 401)
        else row(key, auth).result(clock())
    }

    suspend fun get(fullName: String, forceRefresh: Boolean = false, validate: Boolean = false,
        enrichLanguages: Boolean = false): GitHubCardResult {
        val result = getMetadata(fullName, forceRefresh, validate)
        return if (!validate && enrichLanguages && result.status == GitHubCardStatus.FRESH && result.data != null)
            enrichLanguageBreakdown(fullName) else result
    }

    private suspend fun getMetadata(fullName: String, forceRefresh: Boolean, validate: Boolean): GitHubCardResult = withContext(Dispatchers.IO) {
        val key = requireNotNull(GitHubRepositoryUrl.canonicalFullName(fullName))
        requests.withLock {
            val auth = auth()
            if (auth.unreadable) return@withLock GitHubCardResult(null, GitHubCardStatus.UNAVAILABLE, GitHubCardFailure.CREDENTIALS_UNAVAILABLE)
            if (!validate && auth.scope in invalidScopes.value) return@withLock GitHubCardResult(null, GitHubCardStatus.UNAVAILABLE, GitHubCardFailure.AUTHENTICATION, httpStatus = 401)
            val scopedKey = cacheKey(key, auth)
            val budget = budgets().limit(auth.token != null)
            val now = clock()
            val cached = row(key, auth)
            val data = cached.decode()
            val manualRetry = forceRefresh && cached?.error != null && now >= cached.lastAccessAt + MANUAL_RETRY_DELAY
            if (!validate && !manualRetry && cached != null && now < cached.refreshAfter && (data != null || cached.error != null)) {
                if (cached.error == null && !data.let { it?.isPrivate == true }) dao.touch(scopedKey, now)
                return@withLock cached.result(now)
            }
            var apiState = dao.apiState(auth.scope) ?: GitHubApiCacheStateEntity(id = auth.scope)
            if (now < apiState.blockedUntil) return@withLock GitHubCardResult(data, GitHubCardStatus.RATE_LIMITED, retryAt = apiState.blockedUntil,
                rateLimitKind = GitHubRateLimitKind.SERVER)
            if (now < apiState.windowStartedAt || now - apiState.windowStartedAt >= HOUR) {
                apiState = apiState.copy(windowStartedAt = now, requests = 0)
            }
            if (apiState.requests >= budget) {
                return@withLock GitHubCardResult(data, GitHubCardStatus.RATE_LIMITED, retryAt = apiState.windowStartedAt + HOUR, rateLimitKind = GitHubRateLimitKind.LOCAL)
            }
            // Reserve before dispatch so cancellation/process restart cannot reset the budget.
            apiState = apiState.copy(requests = apiState.requests + 1)
            dao.putApiState(apiState)
            mutableQuota.value = GitHubApiQuota(auth.scope, apiState.requests, budget)
            val request = Request.Builder()
                .url("https://api.github.com".toHttpUrl().newBuilder().addPathSegment("repos")
                    .addPathSegment(key.substringBefore('/')).addPathSegment(key.substringAfter('/')).build())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2026-03-10")
                .header("User-Agent", "RikkaHub-GitHubCards")
                .apply { auth.token?.let { header("Authorization", "Bearer $it") } }
                .apply { if (!validate && data != null) cached?.etag?.let { header("If-None-Match", it) } }
                .build()
            try {
                client.repositoryResponse(request) {
                    if (apiState.requests >= budget) false else {
                        apiState = apiState.copy(requests = apiState.requests + 1)
                        dao.putApiState(apiState)
                        true
                    }
                }.use { response ->
                    if (!current(auth)) return@withLock GitHubCardResult(null, GitHubCardStatus.UNAVAILABLE)
                    if (response.code == 401) {
                        invalidScopes.update { (it + auth.scope).toList().takeLast(50).toSet() }
                        privateRows.value = emptyMap()
                    } else if (response.code == 200 || response.code == 304) {
                        invalidScopes.update { it - auth.scope }
                    }
                    val receivedAt = clock()
                    mutableQuota.value = GitHubApiQuota(auth.scope, apiState.requests, budget,
                        response.header("X-RateLimit-Remaining")?.toLongOrNull(), response.header("X-RateLimit-Limit")?.toLongOrNull(),
                        response.header("X-RateLimit-Reset")?.toLongOrNull()?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000))
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
                            val parsed = try { parseGitHubRepositoryData(source.readUtf8(), allowPrivate = auth.token != null) }
                                catch (_: Exception) { throw InvalidRepositoryResponse() }
                            if (parsed.isPrivate) dao.remove(key)
                            GitHubRepositoryCacheEntity(scopedKey, json.encodeToString(parsed), response.header("ETag"),
                                receivedAt, receivedAt + FRESH_TTL, receivedAt, null)
                        }
                        response.code == 304 && data != null && cached != null ->
                            cached.copy(fetchedAt = receivedAt, refreshAfter = receivedAt + FRESH_TTL,
                                lastAccessAt = receivedAt, etag = response.header("ETag") ?: cached.etag, error = null)
                        else -> GitHubRepositoryCacheEntity(scopedKey,
                            // A 404/410 can mean deleted/newly private; do not expose stale metadata.
                            cached?.payload.takeUnless { response.code in setOf(401, 404, 410) || (response.code == 403 && retryAt == null) }, cached?.etag,
                            cached?.fetchedAt ?: 0, retryAt ?: receivedAt + if (response.code in setOf(404,410)) FRESH_TTL else FAILURE_TTL,
                            receivedAt, if (retryAt != null && response.code in setOf(403, 429))
                                if (response.header("X-RateLimit-Remaining") == "0") "rate_limit:primary" else "rate_limit:secondary"
                                else "http:${response.code}")
                    }
                    if (!store(updated, auth, updated.decode()?.isPrivate == true || data?.isPrivate == true || (auth.token != null && updated.payload == null)))
                        return@withLock GitHubCardResult(null, GitHubCardStatus.UNAVAILABLE)
                    dao.trim()
                    if (!current(auth)) return@withLock GitHubCardResult(null, GitHubCardStatus.UNAVAILABLE)
                    updated.result(receivedAt).also { if (updated.error != null) reportFailure(if (auth.token == null) key else "authenticated", it) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (!current(auth)) return@withLock GitHubCardResult(null, GitHubCardStatus.UNAVAILABLE)
                val failed = GitHubRepositoryCacheEntity(scopedKey, cached?.payload, cached?.etag,
                    cached?.fetchedAt ?: 0, clock() + FAILURE_TTL, clock(), when (error) {
                        is java.net.UnknownHostException -> "dns"
                        is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "timeout"
                        is javax.net.ssl.SSLException -> "tls"
                        is InvalidRepositoryResponse -> "invalid_response"
                        else -> "network"
                    })
                if (!store(failed, auth, data?.isPrivate == true || (auth.token != null && failed.payload == null)))
                    return@withLock GitHubCardResult(null, GitHubCardStatus.UNAVAILABLE)
                dao.trim()
                if (!current(auth)) return@withLock GitHubCardResult(null, GitHubCardStatus.UNAVAILABLE)
                failed.result(clock()).also { reportFailure(if (auth.token == null) key else "authenticated", it) }
            }
        }
    }

    /** Optional OG language bar: same credential fence, serial queue and persisted budget. */
    private suspend fun enrichLanguageBreakdown(fullName: String): GitHubCardResult = withContext(Dispatchers.IO) {
        val key = requireNotNull(GitHubRepositoryUrl.canonicalFullName(fullName))
        requests.withLock {
            val auth = auth()
            if (auth.unreadable || auth.scope in invalidScopes.value) return@withLock cached(fullName)
            val cached = row(key, auth) ?: return@withLock GitHubCardResult(null, GitHubCardStatus.UNAVAILABLE)
            val data = cached.decode() ?: return@withLock cached.result(clock())
            val now = clock()
            val ttl = if (data.languageBreakdownComplete) FRESH_TTL else FAILURE_TTL
            if (data.languageBreakdownAttemptedAt > 0 && now >= data.languageBreakdownAttemptedAt && now - data.languageBreakdownAttemptedAt < ttl)
                return@withLock cached.result(now)
            val budget = budgets().limit(auth.token != null)
            var state = dao.apiState(auth.scope) ?: GitHubApiCacheStateEntity(id = auth.scope)
            if (now < state.blockedUntil) return@withLock cached.result(now)
            if (now < state.windowStartedAt || now - state.windowStartedAt >= HOUR) state = state.copy(windowStartedAt = now, requests = 0)
            if (state.requests >= budget) return@withLock cached.result(now)
            state = state.copy(requests = state.requests + 1)
            dao.putApiState(state)
            if (!current(auth)) return@withLock GitHubCardResult(null, GitHubCardStatus.UNAVAILABLE)
            val canonical = requireNotNull(GitHubRepositoryUrl.canonicalFullName(data.fullName))
            val request = Request.Builder().url("https://api.github.com".toHttpUrl().newBuilder()
                .addPathSegment("repos").addPathSegment(canonical.substringBefore('/')).addPathSegment(canonical.substringAfter('/'))
                .addPathSegment("languages").build())
                .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2026-03-10")
                .header("User-Agent", "RikkaHub-GitHubCards").apply { auth.token?.let { header("Authorization", "Bearer $it") } }.build()
            var updated = data.copy(languageBreakdownAttemptedAt = now, languageBreakdownComplete = false)
            try {
                // Redirects are disabled; no credential can escape to another host or endpoint.
                client.newCall(request).awaitResponse().use { response ->
                    if (!current(auth)) return@withLock GitHubCardResult(null, GitHubCardStatus.UNAVAILABLE)
                    mutableQuota.value = GitHubApiQuota(auth.scope, state.requests, budget,
                        response.header("X-RateLimit-Remaining")?.toLongOrNull(), response.header("X-RateLimit-Limit")?.toLongOrNull(),
                        response.header("X-RateLimit-Reset")?.toLongOrNull()?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000))
                    if (response.code == 401) {
                        invalidScopes.update { (it + auth.scope).toList().takeLast(50).toSet() }
                        privateRows.value = emptyMap()
                        return@withLock GitHubCardResult(null, GitHubCardStatus.UNAVAILABLE, GitHubCardFailure.AUTHENTICATION, httpStatus = 401)
                    }
                    val retryAt = githubRetryAt(response.code, response.headers, clock(),
                        if (response.code == 403) response.peekBody(4096).string() else null)
                    if (retryAt != null) dao.putApiState(state.copy(blockedUntil = retryAt))
                    if (response.code == 200) {
                        val source = response.body.source()
                        if (response.body.contentLength() > 65536) throw InvalidRepositoryResponse()
                        source.request(65537)
                        if (source.buffer.size > 65536) throw InvalidRepositoryResponse()
                        updated = updated.copy(languageBytes = parseGitHubLanguageBytes(source.readUtf8()), languageBreakdownComplete = true)
                    } else reportFailure(if (auth.token == null) key else "authenticated",
                        GitHubCardResult(null, if (retryAt != null) GitHubCardStatus.RATE_LIMITED else GitHubCardStatus.UNAVAILABLE,
                            GitHubCardFailure.HTTP, response.code, retryAt))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                reportFailure(if (auth.token == null) key else "authenticated", GitHubCardResult(null, GitHubCardStatus.UNAVAILABLE,
                    if (error is InvalidRepositoryResponse || error is IllegalArgumentException) GitHubCardFailure.INVALID_RESPONSE else GitHubCardFailure.NETWORK))
            }
            val row = cached.copy(payload = json.encodeToString(updated))
            if (!store(row, auth, updated.isPrivate) || !current(auth)) return@withLock GitHubCardResult(null, GitHubCardStatus.UNAVAILABLE)
            row.result(clock())
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
            (this?.error == "rate_limited" || this?.error?.startsWith("rate_limit:") == true) && now < refreshAfter -> GitHubCardStatus.RATE_LIMITED
            this == null || data == null -> GitHubCardStatus.UNAVAILABLE
            error != null || now >= refreshAfter -> GitHubCardStatus.STALE
            else -> GitHubCardStatus.FRESH
        }, failure = when {
            this?.error == "http:401" -> GitHubCardFailure.AUTHENTICATION
            this?.error == "http:403" -> GitHubCardFailure.PERMISSION
            this?.error?.startsWith("http:") == true -> GitHubCardFailure.HTTP
            this?.error == "dns" -> GitHubCardFailure.DNS
            this?.error == "timeout" -> GitHubCardFailure.TIMEOUT
            this?.error == "tls" -> GitHubCardFailure.TLS
            this?.error == "invalid_response" -> GitHubCardFailure.INVALID_RESPONSE
            this?.error != null && error != "rate_limited" && !error.startsWith("rate_limit:") -> GitHubCardFailure.NETWORK
            else -> null
        }, httpStatus = this?.error?.removePrefix("http:")?.toIntOrNull(),
            retryAt = if (this?.error == "rate_limited" || this?.error?.startsWith("rate_limit:") == true) refreshAfter else if (this?.error != null) lastAccessAt + MANUAL_RETRY_DELAY else null,
            rateLimitKind = when (this?.error) { "rate_limit:primary" -> GitHubRateLimitKind.PRIMARY; "rate_limit:secondary" -> GitHubRateLimitKind.SECONDARY; "rate_limited" -> GitHubRateLimitKind.SERVER; else -> null })
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

internal fun parseGitHubRepositoryData(source: String, allowPrivate: Boolean = false): GitHubRepositoryData {
    val value = Json.parseToJsonElement(source).jsonObject
    fun text(key: String) = (value[key] as? JsonPrimitive)?.contentOrNull
    fun count(key: String) = (value[key] as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0) ?: 0
    val fullName = requireNotNull(text("full_name"))
    require(GitHubRepositoryUrl.canonicalFullName(fullName) != null)
    val private = requireNotNull((value["private"] as? JsonPrimitive)?.booleanOrNull)
    require(!private || allowPrivate)
    return GitHubRepositoryData(fullName, text("description")?.take(500), text("language")?.take(80),
        count("stargazers_count"), count("forks_count"),
        (value["license"] as? JsonObject)?.get("spdx_id")?.jsonPrimitive?.contentOrNull?.takeUnless { it == "NOASSERTION" },
        (value["archived"] as? JsonPrimitive)?.booleanOrNull ?: false,
        (value["fork"] as? JsonPrimitive)?.booleanOrNull ?: false,
        safeGitHubAvatarUrl((value["owner"] as? JsonObject)?.get("avatar_url")?.jsonPrimitive?.contentOrNull), private,
        (value["open_issues_count"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 })
}

internal fun parseGitHubLanguageBytes(source: String): Map<String, Long> {
    val values = Json.parseToJsonElement(source).jsonObject
    require(values.size <= 100)
    return values.map { (name, value) ->
        require(name.isNotBlank() && name.length <= 80)
        val primitive = requireNotNull(value as? JsonPrimitive)
        require(!primitive.isString)
        val bytes = requireNotNull(primitive.longOrNull)
        require(bytes >= 0)
        name to bytes
    }.filter { it.second > 0 }.sortedByDescending { it.second }.toMap()
}

internal fun safeGitHubAvatarUrl(value: String?): String? = value?.let { runCatching { it.toHttpUrl() }.getOrNull() }
    ?.takeIf { it.scheme == "https" && it.host == "avatars.githubusercontent.com" && it.port == 443 &&
        it.username.isEmpty() && it.password.isEmpty() && it.fragment == null && Regex("/u/[0-9]+").matches(it.encodedPath) &&
        it.queryParameterNames.all { name -> name == "v" || name == "s" } }
    ?.toString()

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
