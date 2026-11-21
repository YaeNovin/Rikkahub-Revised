package me.rerere.common.http

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Chinese platform endpoints documented at platform.minimax.cn (2026-11-21). */
object MiniMaxEndpoints {
    const val CHINA_BASE_URL = "https://api.minimax.cn/v1"
    const val CHINA_ANTHROPIC_BASE_URL = "https://api.minimax.cn/anthropic/v1"

    fun isOfficialHost(host: String?): Boolean = host in setOf(
        "api.minimax.cn", "api.minimaxi.com", "api.minimax.io",
        "api.minimax.chat", "api.minimaxi.chat",
    )

    /** Only migrate the former Chinese default, preserving protocol path and user options. */
    fun migrateChineseBaseUrl(baseUrl: String): String {
        val url = baseUrl.trim().toHttpUrlOrNull() ?: return baseUrl
        if (!url.isHttps || url.host != "api.minimaxi.com" || url.port != 443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null ||
            url.encodedPath.trimEnd('/') !in setOf("", "/v1", "/anthropic", "/anthropic/v1")) return baseUrl
        // Preserve path spelling and its trailing slash; do not rewrite proxies
        // or move international accounts to a different platform.
        return url.newBuilder().host("api.minimax.cn").build().toString()
    }
}
