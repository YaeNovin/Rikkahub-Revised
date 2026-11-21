package me.rerere.ai.provider

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

enum class OfficialChatVendor { OPENAI, DEEPSEEK, MINIMAX, KIMI, MIMO, GLM, OTHER }

fun officialChatVendor(baseUrl: String): OfficialChatVendor = when (val host = runCatching { baseUrl.toHttpUrl().host }.getOrNull()) {
    "api.openai.com" -> OfficialChatVendor.OPENAI
    "api.deepseek.com" -> OfficialChatVendor.DEEPSEEK
    "api.moonshot.cn", "api.moonshot.ai", "api.kimi.com" -> OfficialChatVendor.KIMI
    "open.bigmodel.cn", "api.z.ai" -> OfficialChatVendor.GLM
    else -> when {
        me.rerere.common.http.MiniMaxEndpoints.isOfficialHost(host) -> OfficialChatVendor.MINIMAX
        host?.endsWith(".api.openai.com") == true -> OfficialChatVendor.OPENAI
        host == "api.xiaomimimo.com" || host == "token-plan-cn.xiaomimimo.com" || host == "batch-api-cn.xiaomimimo.com" -> OfficialChatVendor.MIMO
        else -> OfficialChatVendor.OTHER
    }
}

fun Model.isMiMo26(): Boolean = Regex("(?:^|/)mimo-v?2[.-]6(?:[-.]|$)")
    .containsMatchIn(parameterModelId().lowercase().replace(' ', '-'))

fun Request.Builder.openAIAuthentication(baseUrl: String, key: String): Request.Builder =
    if (key.isBlank()) this
    else if (officialChatVendor(baseUrl) == OfficialChatVendor.MIMO)
        removeHeader("Authorization").removeHeader("x-api-key").header("api-key", key)
    else header("Authorization", "Bearer $key")

fun Request.Builder.messagesAuthentication(baseUrl: String, key: String): Request.Builder =
    if (key.isBlank()) this
    else if (officialChatVendor(baseUrl) == OfficialChatVendor.MIMO)
        removeHeader("Authorization").removeHeader("x-api-key").header("api-key", key)
    else header("x-api-key", key)

fun ProviderSetting.OpenAI.responsesUrl(): String {
    val url = baseUrl.trimEnd('/').toHttpUrl()
    return if (url.host == "open.bigmodel.cn" && url.encodedPath == "/api/paas/v4")
        url.newBuilder().encodedPath("/api/v1/responses").build().toString()
    else "$baseUrl".trimEnd('/') + "/responses"
}
