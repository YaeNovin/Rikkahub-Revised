package me.rerere.common.http

import org.junit.Assert.*
import org.junit.Test

class MiniMaxEndpointsTest {
    @Test fun formerChineseBasesKeepTheirProtocolPaths() {
        for (path in listOf("", "/", "/v1", "/v1/", "/anthropic", "/anthropic/v1", "/anthropic/v1/")) {
            val expected = "https://api.minimax.cn" + path.ifEmpty { "/" }
            assertEquals(expected, MiniMaxEndpoints.migrateChineseBaseUrl("https://api.minimaxi.com$path"))
        }
    }

    @Test fun migrationIsIdempotentAndPreservesInternationalAndCustomRoutes() {
        for (url in listOf("https://api.minimax.cn/v1", "https://api.minimax.io/v1", "https://api-uw.minimax.io/v1",
            "https://gateway.test/v1", "https://api.minimaxi.com.gateway.test/v1", "https://api.minimaxi.com/custom/v1",
            "https://api.minimaxi.com/v1?tenant=test", "https://api.minimaxi.com/v1#anchor",
            "https://api.minimaxi.com:8443/v1", "http://api.minimaxi.com/v1", "https://" + "user:example@" + "api.minimaxi.com/v1", "invalid")) {
            assertEquals(url, MiniMaxEndpoints.migrateChineseBaseUrl(url))
        }
        val migrated = MiniMaxEndpoints.migrateChineseBaseUrl("https://API.MINIMAXI.COM:443/anthropic/v1")
        assertEquals("https://api.minimax.cn/anthropic/v1", migrated)
        assertEquals(migrated, MiniMaxEndpoints.migrateChineseBaseUrl(migrated))
    }

    @Test fun officialHostDetectionDoesNotMatchUrlsOrLookalikes() {
        for (host in listOf("api.minimax.cn", "api.minimaxi.com", "api.minimax.io", "api.minimax.chat", "api.minimaxi.chat"))
            assertTrue(MiniMaxEndpoints.isOfficialHost(host))
        for (host in listOf(null, "gateway.test", "api.minimax.cn.gateway.test", "https://api.minimax.cn", "api.minimax.cn@evil.test"))
            assertFalse(MiniMaxEndpoints.isOfficialHost(host))
    }
}
