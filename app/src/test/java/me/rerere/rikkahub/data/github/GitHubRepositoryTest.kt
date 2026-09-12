package me.rerere.rikkahub.data.github

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import me.rerere.rikkahub.data.db.dao.GitHubRepositoryCacheDAO
import me.rerere.rikkahub.data.db.entity.GitHubRepositoryCacheEntity
import me.rerere.rikkahub.data.db.entity.GitHubApiCacheStateEntity
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class GitHubRepositoryTest {
    private val metadata = """{"full_name":"Owner/Repo","private":false,"description":"Description 中文","language":"Kotlin","stargazers_count":100,"forks_count":5,"license":null,"archived":true,"fork":false}"""
    private fun client(block: (Request) -> Response) = OkHttpClient.Builder().addInterceptor { block(it.request()) }.build()
    private fun response(request: Request, code: Int = 200, body: String = metadata, headers: Headers = Headers.Builder().build()) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .headers(headers).body(body.toResponseBody("application/json".toMediaType())).build()

    @Test fun `strict links exclude non repository routes and normalize case`() {
        listOf("https://github.com/Owner/Repo", "https://github.com/Owner/Repo/", "http://www.github.com/Owner/Repo").forEach {
            assertEquals("owner/repo", GitHubRepositoryUrl.parse(it))
        }
        listOf("https://github.com/owner", "https://github.com/owner/repo/tree/main", "https://github.com/owner/repo/blob/main/README.md",
            "https://github.com/owner/repo/issues/12", "https://github.com/owner/repo/pull/12", "https://github.com/owner/repo/releases",
            "https://github.com/owner/repo/actions", "https://github.com/owner/repo/wiki", "https://github.com/topics/kotlin",
            "https://github.com/orgs/openai", "https://github.com/settings/profile", "https://github.com/features/copilot",
            "https://github.com/owner/repo?tab=issues", "https://github.com/owner/repo#readme", "https://github.com/owner/repo.git",
            "https://github.com/owner//repo", "https://github.com//owner/repo", "https://github.com/owner/repo//",
            "https://github.com/owner/%2e%2e", "https://github.com/owner/%72epo", "https://github.com/owner/..",
            "https://github.com.evil.test/owner/repo", "https://github.com@evil.test/owner/repo", "https://u:p@github.com/owner/repo",
            "https://github.com:123/owner/repo", "file://github.com/owner/repo", "https://api.github.com/repos/owner/repo")
            .forEach { assertNull(it, GitHubRepositoryUrl.parse(it)) }
    }

    @Test fun `nullable metadata parses without treating absent counts as fabricated values`() {
        val data = parseGitHubRepositoryData(metadata)
        assertNull(data.license)
        assertEquals("Description 中文", data.description)
        assertEquals(100L, data.stars)
        assertTrue(data.archived)
        assertTrue(runCatching { parseGitHubRepositoryData("""{"message":"Not found"}""") }.isFailure)
        assertTrue(runCatching { parseGitHubRepositoryData(metadata.replace("false", "true")) }.isFailure)
    }

    @Test fun `concurrent repeated links and repository restart use one persisted fetch`() = runBlocking {
        val dao = MemoryGitHubDAO()
        val count = AtomicInteger()
        val client = client { request ->
            count.incrementAndGet()
            assertEquals("https://api.github.com/repos/owner/repo", request.url.toString())
            assertEquals("application/vnd.github+json", request.header("Accept"))
            assertEquals("2026-03-10", request.header("X-GitHub-Api-Version"))
            assertNull(request.header("Authorization"))
            assertNull(request.header("Cookie"))
            response(request)
        }
        val repository = GitHubRepository(dao, client) { 10_000_000 }
        val results = (1..20).map { async { repository.get("Owner/Repo") } }.awaitAll()
        assertTrue(results.all { it.status == GitHubCardStatus.FRESH && it.data?.stars == 100L })
        assertEquals(1, count.get())
        assertEquals(GitHubCardStatus.FRESH, GitHubRepository(dao, client) { 10_000_001 }.get("owner/repo").status)
        assertEquals(1, count.get())
    }

    @Test fun `etag refresh and 304 renew metadata without decoding empty bodies`() = runBlocking {
        var now = 10_000_000L
        val dao = MemoryGitHubDAO()
        val count = AtomicInteger()
        val repository = GitHubRepository(dao, client { request ->
            if (count.incrementAndGet() == 1) response(request, headers = Headers.headersOf("ETag", "\"v1\""))
            else {
                assertEquals("\"v1\"", request.header("If-None-Match"))
                response(request, 304, "")
            }
        }) { now }
        repository.get("owner/repo")
        now += GitHubRepository.FRESH_TTL + 1
        assertEquals(GitHubCardStatus.STALE, repository.cached("owner/repo").status)
        assertEquals(GitHubCardStatus.FRESH, repository.get("owner/repo").status)
        assertEquals(now, dao.get("owner/repo")!!.fetchedAt)
        assertEquals(2, count.get())
    }

    @Test fun `rate limit blocks all repos across repository recreation and respects reset`() = runBlocking {
        var now = 10_000_000L
        val dao = MemoryGitHubDAO()
        val count = AtomicInteger()
        val reset = now + 900_000
        val client = client { req -> count.incrementAndGet(); response(req, 403, "{}", Headers.headersOf(
            "X-RateLimit-Remaining", "0", "X-RateLimit-Reset", (reset / 1000).toString(), "Retry-After", "60")) }
        assertEquals(GitHubCardStatus.RATE_LIMITED, GitHubRepository(dao, client) { now }.get("owner/repo").status)
        now += 61_000
        assertEquals(GitHubCardStatus.RATE_LIMITED, GitHubRepository(dao, client) { now }.get("other/repo").status)
        assertEquals(reset, dao.apiState()!!.blockedUntil)
        assertEquals(1, count.get())
    }

    @Test fun `successful last available response blocks next new repo but preserves cached data`() = runBlocking {
        val dao = MemoryGitHubDAO()
        val count = AtomicInteger()
        val repository = GitHubRepository(dao, client { req -> count.incrementAndGet(); response(req, headers = Headers.headersOf("X-RateLimit-Remaining", "0")) }) { 10_000_000 }
        assertEquals(GitHubCardStatus.FRESH, repository.get("owner/repo").status)
        assertEquals(GitHubCardStatus.FRESH, repository.get("owner/repo").status)
        assertEquals(GitHubCardStatus.RATE_LIMITED, repository.get("other/repo").status)
        assertEquals(1, count.get())
    }

    @Test fun `offline keeps stale metadata and cools down repeat requests`() = runBlocking {
        var now = 10_000_000L
        val dao = MemoryGitHubDAO()
        val count = AtomicInteger()
        val repository = GitHubRepository(dao, client { req ->
            if (count.incrementAndGet() == 1) response(req) else throw IOException("offline")
        }) { now }
        repository.get("owner/repo")
        now += GitHubRepository.FRESH_TTL + 1
        val result = repository.get("owner/repo")
        assertEquals(GitHubCardStatus.STALE, result.status)
        assertEquals(100L, result.data!!.stars)
        repository.get("owner/repo")
        assertEquals(2, count.get())
    }

    @Test fun `404 removes prior metadata and negative cache prevents repeated misses`() = runBlocking {
        var now = 10_000_000L
        val dao = MemoryGitHubDAO()
        val count = AtomicInteger()
        val repository = GitHubRepository(dao, client { req ->
            if (count.incrementAndGet() == 1) response(req) else response(req, 404, "{}")
        }) { now }
        repository.get("owner/repo")
        now += GitHubRepository.FRESH_TTL + 1
        assertNull(repository.get("owner/repo").data)
        assertNull(repository.get("owner/repo").data)
        assertEquals(2, count.get())
    }

    @Test fun `local budget and cache reads avoid unnecessary HTTP calls`() = runBlocking {
        val now = 10_000_000L
        val dao = MemoryGitHubDAO()
        dao.putApiState(GitHubApiCacheStateEntity(windowStartedAt = now, requests = 50))
        val count = AtomicInteger()
        val repository = GitHubRepository(dao, client { req -> count.incrementAndGet(); response(req) }) { now }
        assertNull(repository.cached("owner/repo").data)
        assertEquals(GitHubCardStatus.RATE_LIMITED, repository.get("owner/repo").status)
        assertEquals(0, count.get())
        assertEquals(now + GitHubRepository.HOUR, dao.apiState()!!.blockedUntil)
    }

    @Test fun `oversized response and corrupted JSON are not cached as repository success`() = runBlocking {
        for (body in listOf("x".repeat(512 * 1024 + 1), "{broken")) {
            val dao = MemoryGitHubDAO()
            val repository = GitHubRepository(dao, client { req -> response(req, body = body) })
            assertEquals(GitHubCardStatus.UNAVAILABLE, repository.get("owner/repo").status)
            assertNull(dao.get("owner/repo")!!.payload)
        }
    }

    @Test fun `cancelled UI request does not become an unavailable cache entry`() = runBlocking {
        val dao = MemoryGitHubDAO()
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val repository = GitHubRepository(dao, client {
            started.complete(Unit)
            release.await(3, TimeUnit.SECONDS)
            throw IOException("cancelled")
        }) { 10_000_000 }
        val job = launch { repository.get("owner/repo") }
        try {
            withTimeout(3000) { started.await() }
            job.cancelAndJoin()
            assertNull(dao.get("owner/repo"))
            assertEquals(1, dao.apiState()!!.requests)
        } finally { release.countDown(); job.cancel() }
    }

    @Test fun `secondary rate limits support seconds HTTP dates and minimum delay`() {
        val now = 1_000_000L
        assertNull(githubRetryAt(403, Headers.Builder().build(), now))
        assertEquals(now + 60_000, githubRetryAt(403, Headers.Builder().build(), now, "You have exceeded a secondary rate limit"))
        assertEquals(now + 180_000, githubRetryAt(429, Headers.headersOf("Retry-After", "180"), now))
        val date = java.time.Instant.ofEpochMilli(now + 240_000).atZone(java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
        assertEquals(now + 240_000, githubRetryAt(429, Headers.headersOf("Retry-After", date), now))
        assertNull(githubRetryAt(404, Headers.Builder().build(), now))
    }

    @Test fun `manual retry recovers cached network failure but cannot bypass throttling`() = runBlocking {
        var now = 10_000_000L
        val dao = MemoryGitHubDAO()
        val calls = AtomicInteger()
        val repository = GitHubRepository(dao, client { request ->
            if (calls.incrementAndGet() == 1) throw java.net.UnknownHostException("test")
            response(request)
        }) { now }
        val failed = repository.get("owner/repo")
        assertEquals(GitHubCardFailure.DNS, failed.failure)
        assertEquals(now + 5000, failed.retryAt)
        repository.get("owner/repo", forceRefresh = true)
        assertEquals(1, calls.get())
        now += 5001
        assertEquals(GitHubCardStatus.FRESH, repository.get("owner/repo", forceRefresh = true).status)
        assertEquals(2, calls.get())
        dao.putApiState(GitHubApiCacheStateEntity(blockedUntil = now + 100_000))
        assertEquals(GitHubCardStatus.RATE_LIMITED, repository.get("other/repo", forceRefresh = true).status)
        assertEquals(2, calls.get())
    }

    @Test fun `ordinary forbidden response does not block other repositories`() = runBlocking {
        val dao = MemoryGitHubDAO()
        val repository = GitHubRepository(dao, client { request ->
            if (request.url.encodedPath.contains("forbidden")) response(request, 403, """{"message":"Forbidden"}""") else response(request)
        }) { 10_000_000 }
        val denied = repository.get("owner/forbidden")
        assertEquals(GitHubCardStatus.UNAVAILABLE, denied.status)
        assertEquals(403, denied.httpStatus)
        assertEquals(0L, dao.apiState()!!.blockedUntil)
        assertEquals(GitHubCardStatus.FRESH, repository.get("owner/repo").status)
    }

    @Test fun `official repository redirect works without forwarding credentials and counts each request`() = runBlocking {
        val dao = MemoryGitHubDAO()
        val calls = AtomicInteger()
        val repository = GitHubRepository(dao, client { request ->
            calls.incrementAndGet()
            assertNull(request.header("Authorization"))
            assertNull(request.header("Cookie"))
            if (request.url.encodedPath.startsWith("/repos/")) response(request, 301, "", Headers.headersOf("Location", "https://api.github.com/repositories/123"))
            else response(request)
        }) { 10_000_000 }
        assertEquals(GitHubCardStatus.FRESH, repository.get("owner/repo").status)
        assertEquals(2, calls.get())
        assertEquals(2, dao.apiState()!!.requests)
    }

    @Test fun `unsafe redirects and non repository API targets are never followed`() = runBlocking {
        for (location in listOf("https://example.com/repos/owner/repo", "http://api.github.com/repositories/12",
            "https://api.github.com/user", "https://user:password@api.github.com/repos/owner/repo",
            "https://api.github.com/repos/owner/repo?token=secret")) {
            val calls = AtomicInteger()
            val repository = GitHubRepository(MemoryGitHubDAO(), client { request ->
                calls.incrementAndGet(); response(request, 301, "", Headers.headersOf("Location", location))
            }) { 10_000_000 }
            assertEquals(301, repository.get("owner/repo").httpStatus)
            assertEquals(1, calls.get())
        }
    }
}

private class MemoryGitHubDAO : GitHubRepositoryCacheDAO {
    private val rows = ConcurrentHashMap<String, GitHubRepositoryCacheEntity>()
    private val changes = MutableStateFlow(0L)
    private var state: GitHubApiCacheStateEntity? = null
    override suspend fun get(fullName: String) = rows[fullName]
    override fun observe(fullName: String) = changes.map { rows[fullName] }
    override suspend fun put(entity: GitHubRepositoryCacheEntity) { rows[entity.fullName] = entity; changes.value++ }
    override suspend fun touch(fullName: String, now: Long) { rows.computeIfPresent(fullName) { _, row -> row.copy(lastAccessAt = now) } }
    override suspend fun apiState() = state
    override suspend fun putApiState(state: GitHubApiCacheStateEntity) { this.state = state }
    override suspend fun trim() = Unit
}
