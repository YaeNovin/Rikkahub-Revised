package me.rerere.search

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class VolcengineSearchServiceTest {
    private fun params(query: String = "Kotlin") = buildJsonObject { put("query", query) }
    @Test fun `custom endpoint and casing match official protocol and filters do not leak to global`() {
        val custom = SearchServiceOptions.VolcengineOptions(sites = "kotlinlang.org|developer.android.com",
            blockHosts = "example.com", timeRange = "OneWeek", industry = "gov", needContent = true,
            authInfoLevel = 1, queryRewrite = true, contentFormats = "markdown", enableWaiting = true, maxWaitTime = 15000)
        val body = buildVolcengineSearchRequest(params(), 99, custom)
        assertEquals("https://open.feedcoopapi.com/search_api/web_search", volcengineSearchEndpoint(custom))
        assertEquals(50, body.getValue("Count").jsonPrimitive.int)
        assertEquals(10000, body.getValue("MaxWaitTime").jsonPrimitive.int)
        assertEquals("OneWeek", body.getValue("TimeRange").jsonPrimitive.content)
        assertEquals(true, body.getValue("Filter").jsonObject.getValue("NeedContent").jsonPrimitive.boolean)
        assertEquals("gov", body.getValue("Industry").jsonPrimitive.content)
        val global = buildVolcengineSearchRequest(params(), 99, custom.copy(global = true))
        assertEquals(20, global.getValue("DocCount").jsonPrimitive.int)
        assertFalse(global.containsKey("Count"))
        assertFalse(global.containsKey("TimeRange"))
        assertFalse(global.getValue("Filter").jsonObject.containsKey("Sites"))
    }
    @Test fun `global visual search sends image and validated region without requiring text`() {
        val request = buildVolcengineSearchRequest(buildJsonObject {
            put("image_url", "https://example.com/input.jpg")
            put("region_of_interest", Json.parseToJsonElement("""{"XMin":0.1,"YMin":0.2,"XMax":0.8,"YMax":0.9}"""))
        }, 10, SearchServiceOptions.VolcengineOptions(global = true, searchType = "visual"))
        assertEquals("visual", request.getValue("SearchType").jsonPrimitive.content)
        assertEquals("https://example.com/input.jpg", request.getValue("ImageQuery").jsonObject.getValue("Url").jsonPrimitive.content)
        assertTrue(request.getValue("ImageQuery").jsonObject.containsKey("RegionOfInterest"))
    }
    @Test fun `invalid image input time ranges and unsupported edition combinations fail locally`() {
        assertThrows(IllegalArgumentException::class.java) { buildVolcengineSearchRequest(params(), 10, SearchServiceOptions.VolcengineOptions(searchType = "visual")) }
        assertThrows(IllegalArgumentException::class.java) { buildVolcengineSearchRequest(params(), 10, SearchServiceOptions.VolcengineOptions(global = true, searchType = "visual")) }
        assertThrows(IllegalArgumentException::class.java) { buildVolcengineSearchRequest(params(), 10, SearchServiceOptions.VolcengineOptions(timeRange = "2026-11-02..2026-01-01")) }
        assertThrows(IllegalArgumentException::class.java) { buildVolcengineSearchRequest(params("😀".repeat(101)), 10, SearchServiceOptions.VolcengineOptions()) }
    }
    @Test fun `custom results prefer full content preserve cards and collect inline images`() {
        val result = parseVolcengineSearchResponse("""{"Result":{"WebResults":[
            {"Title":"Page","Url":"https://example.com","Content":"full","Summary":"summary","Snippet":"short","InlineImages":[{"ImageUrl":"https://example.com/a.png"}]},
            {"Title":"Weather","SiteName":"火山如意","Summary":"sunny"}]}}""", false)
        assertEquals(listOf("full", "sunny"), result.items.map { it.text })
        assertEquals(listOf("https://example.com/a.png"), result.images)
    }
    @Test fun `global mixed text image snippets and missing title are supported`() {
        val result = parseVolcengineSearchResponse("""{"Result":{"ErrorCode":0,"Documents":[{"Url":"https://example.com","Snippet":[
            {"Type":"text","Text":"source"},{"Type":"image","Image":{"ImageUrl":"https://example.com/a.png","Alt":"caption"}}]}]}}""", true)
        assertEquals("source\n\ncaption", result.items.single().text)
        assertEquals("https://example.com", result.items.single().title)
        assertEquals(1, result.images.size)
    }
    @Test fun `successful http business failures are surfaced rather than empty search results`() {
        assertThrows(IllegalArgumentException::class.java) { parseVolcengineSearchResponse("""{"Result":{"ErrorCode":700901,"ErrorMsg":"bad key"}}""", true) }
        assertThrows(IllegalStateException::class.java) { parseVolcengineSearchResponse("""{"ResponseMetadata":{"RequestId":"test","Error":{"Code":"10403","Message":"denied"}}} """, false) }
    }
    @Test fun `serialized options survive restart and register correct service`() {
        val options: SearchServiceOptions = SearchServiceOptions.VolcengineOptions(global = true, searchType = "image", maxSnippetLength = 700)
        val restored = Json.decodeFromString<SearchServiceOptions>(Json.encodeToString(options))
        assertEquals(options, restored)
        assertSame(VolcengineSearchService, SearchService.getService(restored))
        assertTrue(SearchServiceOptions.TYPES.containsKey(restored::class))
    }
}
