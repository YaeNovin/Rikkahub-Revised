package me.rerere.rikkahub.data.github

import javax.crypto.KeyGenerator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GitHubAuthenticationTest {
    private val public = """{"full_name":"Owner/Repo","private":false,"description":"public","stargazers_count":12,"forks_count":3,"owner":{"avatar_url":"https://avatars.githubusercontent.com/u/1?v=4"}}"""
    private val privateRepo = public.replace("\"private\":false", "\"private\":true").replace("\"public\"", "\"confidential description\"")
    private fun client(block: (Request) -> Response) = OkHttpClient.Builder().addInterceptor { block(it.request()) }.build()
    private fun response(request: Request, code: Int = 200, body: String = public, headers: Headers = Headers.Builder().build()) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .headers(headers).body(body.toResponseBody("application/json".toMediaType())).build()

    @Test fun `token is sent only to validated GitHub API redirects and bypasses anonymous fifty budget`() = runBlocking {
        val source = TestGitHubCredentials("test-credential")
        val dao = MemoryGitHubDAO()
        var calls = 0
        val repository = GitHubRepository(dao, client { request ->
            calls++
            assertEquals("api.github.com", request.url.host)
            assertEquals("Bearer test-credential", request.header("Authorization"))
            assertNull(request.header("Cookie"))
            response(request)
        }, credentials = source, clock = { 1_000_000 })
        repeat(51) { assertEquals(GitHubCardStatus.FRESH, repository.get("owner/repo$it").status) }
        assertEquals(51, calls)
        assertEquals(51, dao.apiState(source.read().scope)!!.requests)
        assertNull(dao.apiState())
        assertFalse(source.read().scope.contains("test-credential"))
        assertFalse(source.read().toString().contains("test-credential"))
    }

    @Test fun `budgets are isolated by identity and raising local budget permits requests`() = runBlocking {
        val source = TestGitHubCredentials()
        var budget = GitHubRequestBudgets(1, 2)
        var calls = 0
        val repository = GitHubRepository(MemoryGitHubDAO(), client { calls++; response(it) }, credentials = source, budgets = { budget }, clock = { 1_000_000 })
        repository.get("owner/one")
        assertEquals(GitHubRateLimitKind.LOCAL, repository.get("owner/two").rateLimitKind)
        budget = budget.copy(anonymous = 2)
        assertEquals(GitHubCardStatus.FRESH, repository.get("owner/two").status)
        source.replace("second-identity")
        assertEquals(GitHubCardStatus.FRESH, repository.get("owner/three").status)
        source.replace(null)
        assertEquals(GitHubRateLimitKind.LOCAL, repository.get("owner/four").rateLimitKind)
        assertEquals(3, calls)
    }

    @Test fun `authentication permissions primary and secondary limits remain distinct`() = runBlocking {
        val cases = listOf(
            Triple(401, Headers.headersOf(), GitHubCardFailure.AUTHENTICATION),
            Triple(403, Headers.headersOf(), GitHubCardFailure.PERMISSION),
        )
        for ((code, headers, expected) in cases) {
            val repo = GitHubRepository(MemoryGitHubDAO(), client { response(it, code, "{}", headers) }, credentials = TestGitHubCredentials("credential"))
            assertEquals(expected, repo.get("owner/repo").failure)
        }
        for ((headers, expected) in listOf(
            Headers.headersOf("X-RateLimit-Remaining", "0", "X-RateLimit-Reset", "2000") to GitHubRateLimitKind.PRIMARY,
            Headers.headersOf("Retry-After", "120") to GitHubRateLimitKind.SECONDARY,
        )) {
            val repo = GitHubRepository(MemoryGitHubDAO(), client { response(it, 403, "{}", headers) }, credentials = TestGitHubCredentials("credential"), clock = { 1_000_000 })
            val result = repo.get("owner/repo")
            assertEquals(GitHubCardStatus.RATE_LIMITED, result.status)
            assertEquals(expected, result.rateLimitKind)
        }
    }

    @Test fun `private metadata never reaches Room and token removal drops the session preview`() = runBlocking {
        val source = TestGitHubCredentials("private-credential")
        val dao = MemoryGitHubDAO()
        val repo = GitHubRepository(dao, client { response(it, body = privateRepo) }, credentials = source)
        val result = repo.get("owner/repo")
        assertTrue(result.data!!.isPrivate)
        assertNull(dao.get("owner/repo"))
        assertNull(dao.get(source.read().scope + ":owner/repo"))
        assertEquals(result.data, repo.cached("owner/repo").data)
        source.replace(null)
        assertNull(repo.cached("owner/repo").data)
        assertNull(repo.observe("owner/repo").first().data)
    }

    @Test fun `anonymous responses cannot smuggle private metadata into persistent cache`() = runBlocking {
        val dao = MemoryGitHubDAO()
        val repo = GitHubRepository(dao, client { response(it, body = privateRepo) })
        val result = repo.get("owner/repo")
        assertNull(result.data)
        assertEquals(GitHubCardFailure.INVALID_RESPONSE, result.failure)
        assertNull(dao.get("owner/repo")!!.payload)
    }

    @Test fun `known invalid token clears private cards and stops repeated calls until explicit validation`() = runBlocking {
        val source = TestGitHubCredentials("revoked-credential")
        var calls = 0
        val repo = GitHubRepository(MemoryGitHubDAO(), client { request ->
            calls++
            if (calls == 1) response(request, body = privateRepo) else response(request, 401, "{}")
        }, credentials = source)
        assertNotNull(repo.get("owner/repo").data)
        assertEquals(GitHubCardFailure.AUTHENTICATION, repo.get("owner/another").failure)
        assertNull(repo.cached("owner/repo").data)
        assertEquals(GitHubCardFailure.AUTHENTICATION, repo.observe("owner/repo").first().failure)
        repo.get("owner/third")
        assertEquals(2, calls)
        repo.get("owner/third", validate = true)
        assertEquals(3, calls)
    }

    @Test fun `token replacement while response is in flight discards the old result`() = runBlocking {
        val source = TestGitHubCredentials("old-credential")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dao = MemoryGitHubDAO()
        val repo = GitHubRepository(dao, client { request ->
            started.countDown(); check(release.await(5, TimeUnit.SECONDS)); response(request, body = privateRepo)
        }, credentials = source)
        val pending = async(Dispatchers.Default) { repo.get("owner/repo") }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        source.replace(null)
        release.countDown()
        assertNull(pending.await().data)
        assertNull(repo.cached("owner/repo").data)
        assertNull(dao.get("owner/repo"))
    }

    @Test fun `AES GCM has fresh IVs authenticates ciphertext and never stores plaintext`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val secret = "synthetic-secret-for-crypto-test"
        val a = GitHubTokenCipher.encrypt(secret, key)
        val b = GitHubTokenCipher.encrypt(secret, key)
        assertFalse(a.contentEquals(b))
        assertFalse(a.toString(Charsets.ISO_8859_1).contains(secret))
        assertEquals(secret, GitHubTokenCipher.decrypt(a, key))
        a[a.lastIndex] = (a.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { GitHubTokenCipher.decrypt(a, key) }
    }

    @Test fun `avatar URLs never carry credentials or arbitrary hosts`() {
        assertNotNull(safeGitHubAvatarUrl("https://avatars.githubusercontent.com/u/1?v=4&s=80"))
        val withUserInfo = java.net.URI("https", "user:pass", "avatars.githubusercontent.com", -1, "/u/1", null, null).toString()
        for (url in listOf("https://evil.example/u/1", "http://avatars.githubusercontent.com/u/1", "https://avatars.githubusercontent.com/u/1?token=secret", withUserInfo))
            assertNull(safeGitHubAvatarUrl(url))
    }
}

private class TestGitHubCredentials(private var token: String? = null) : GitHubCredentialSource {
    override val state = MutableStateFlow(GitHubTokenState(configured = token != null))
    override suspend fun read() = GitHubCredentials(token, state.value.revision)
    fun replace(value: String?) { token = value; state.value = GitHubTokenState(configured = value != null, revision = state.value.revision + 1) }
}
