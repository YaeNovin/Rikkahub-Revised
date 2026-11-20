package me.rerere.rikkahub.data.github

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class GitHubSocialMetadataTest {
    private val metadata = """{"full_name":"Owner/Repo","private":false,"description":"OG fixture","language":"Kotlin","stargazers_count":4000,"forks_count":393,"open_issues_count":25}"""
    private val languages = """{"Kotlin":700,"Java":200,"Shell":100}"""
    private fun client(block: (Request) -> Response) = OkHttpClient.Builder().addInterceptor { block(it.request()) }.build()
    private fun response(request: Request, body: String, code: Int = 200, headers: Headers = Headers.Builder().build()) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture").headers(headers)
            .body(body.toResponseBody("application/json".toMediaType())).build()

    @Test fun `language enrichment uses one budgeted call and survives public cache reload`() = runBlocking {
        val dao = MemoryGitHubDAO(); var calls = 0
        val api = client { calls++; response(it, if (it.url.encodedPath.endsWith("/languages")) languages else metadata) }
        val repo = GitHubRepository(dao, api)
        val result = repo.get("owner/repo", enrichLanguages = true)
        assertEquals(25L, result.data!!.openIssuesAndPullRequests)
        assertEquals(mapOf("Kotlin" to 700L, "Java" to 200L, "Shell" to 100L), result.data!!.languageBytes)
        assertTrue(result.data!!.languageBreakdownComplete)
        assertEquals(2, calls); assertEquals(2, dao.apiState()!!.requests)
        assertEquals(result.data, GitHubRepository(dao, api).get("owner/repo", enrichLanguages = true).data)
        assertEquals(2, calls)
    }

    @Test fun `language fetch never spends beyond budget and can resume after local increase`() = runBlocking {
        var budget = GitHubRequestBudgets(1, 4500); var calls = 0
        val repo = GitHubRepository(MemoryGitHubDAO(), client { calls++; response(it, if (it.url.encodedPath.endsWith("/languages")) languages else metadata) }, budgets = { budget })
        assertFalse(repo.get("owner/repo", enrichLanguages = true).data!!.languageBreakdownComplete)
        assertEquals(1, calls)
        budget = budget.copy(anonymous = 2)
        assertTrue(repo.get("owner/repo", enrichLanguages = true).data!!.languageBreakdownComplete)
        assertEquals(2, calls)
    }

    @Test fun `optional language rate limit retains card and blocks further API work`() = runBlocking {
        var calls = 0
        val repo = GitHubRepository(MemoryGitHubDAO(), client {
            calls++
            if (it.url.encodedPath.endsWith("/languages")) response(it, "{}", 429, Headers.headersOf("Retry-After", "120")) else response(it, metadata)
        }, clock = { 1_000_000 })
        assertEquals(GitHubCardStatus.FRESH, repo.get("owner/repo", enrichLanguages = true).status)
        assertEquals(GitHubCardStatus.RATE_LIMITED, repo.get("owner/another").status)
        assertEquals(2, calls)
    }

    @Test fun `language failure is cached briefly and does not discard usable metadata`() = runBlocking {
        var now = 1_000_000L; var calls = 0; var fail = true
        val repo = GitHubRepository(MemoryGitHubDAO(), client {
            calls++
            if (it.url.encodedPath.endsWith("/languages")) response(it, if (fail) "{}" else languages, if (fail) 403 else 200) else response(it, metadata)
        }, clock = { now })
        assertNotNull(repo.get("owner/repo", enrichLanguages = true).data)
        repo.get("owner/repo", enrichLanguages = true); assertEquals(2, calls)
        now += GitHubRepository.FAILURE_TTL + 1; fail = false
        assertTrue(repo.get("owner/repo", enrichLanguages = true).data!!.languageBreakdownComplete)
        assertEquals(3, calls)
    }

    @Test fun `private languages remain in memory and auth rejection clears the card`() = runBlocking {
        val source = object : GitHubCredentialSource {
            override val state = MutableStateFlow(GitHubTokenState(configured = true))
            override suspend fun read() = GitHubCredentials("synthetic-private-token")
        }
        val dao = MemoryGitHubDAO(); var denied = false
        val repo = GitHubRepository(dao, client {
            assertEquals("api.github.com", it.url.host); assertEquals("Bearer synthetic-private-token", it.header("Authorization"))
            response(it, if (it.url.encodedPath.endsWith("/languages")) languages else metadata.replace("false", "true"),
                if (denied && it.url.encodedPath.endsWith("/languages")) 401 else 200)
        }, credentials = source)
        assertTrue(repo.get("owner/repo", enrichLanguages = true).data!!.isPrivate)
        assertNull(dao.get(source.read().scope + ":owner/repo"))
        denied = true
        assertEquals(GitHubCardFailure.AUTHENTICATION, repo.get("owner/second", enrichLanguages = true).failure)
        assertNull(repo.cached("owner/repo").data)
    }

    @Test fun `language schema rejects invalid byte counts and does not overflow totals`() {
        for (body in listOf("{\"Kotlin\":-1}", "{\"Kotlin\":\"700\"}", "{\"Kotlin\":1.5}", "[]", "{bad"))
            assertThrows(Exception::class.java) { parseGitHubLanguageBytes(body) }
        assertTrue(parseGitHubLanguageBytes("{}").isEmpty())
        val large = parseGitHubLanguageBytes("{\"Kotlin\":9223372036854775807,\"Java\":9223372036854775807}")
        assertTrue(large.values.sumOf { it.toDouble() }.isFinite())
    }
}
