package me.rerere.ai.provider.providers.openai

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.parameterModelId
import okhttp3.HttpUrl.Companion.toHttpUrl

fun validImageApiPath(path: String): Boolean = path.isBlank() ||
    (path.startsWith('/') && !path.startsWith("//") &&
        path.none { it.isWhitespace() || it in "?#\\:%" } &&
        path.split('/').none { it == "." || it == ".." })

internal fun imageApiUrl(baseUrl: String, path: String): String {
    require(path.isNotBlank() && validImageApiPath(path)) { "图像 API 路径需使用 / 开头的相对路径" }
    val base = baseUrl.trimEnd('/').toHttpUrl()
    val prefix = base.encodedPath.trimEnd('/')
    val suffix = if (prefix.isNotEmpty() && path.startsWith("$prefix/")) path.removePrefix(prefix) else path
    return base.newBuilder().encodedPath(prefix + suffix).query(null).fragment(null).build().toString()
}

internal fun ProviderSetting.OpenAI.imageApiPath(model: Model, edit: Boolean = false,
    generationEndpointForEdit: Boolean = false): String {
    val explicit = if (edit) model.imageEditPath else model.imageGenerationPath
    explicit?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
    val host = baseUrl.trimEnd('/').toHttpUrl().host
    // This is a supplier protocol, not an OpenAI model-wide endpoint.
    val lingkeImage = host in setOf("api.lk888.ai", "api.lk666.ai") &&
        (model.modelId.lowercase().startsWith("tt-image-2.6") || isGptImage26(model.parameterModelId()) ||
            model.parameterModelId().lowercase() == "gpt-image-2.6")
    return if (lingkeImage) "/media/generate"
        else if (edit && !generationEndpointForEdit) "/images/edits" else "/images/generations"
}

internal fun isMediaImagePath(path: String): Boolean = path.trimEnd('/').endsWith("/media/generate")
