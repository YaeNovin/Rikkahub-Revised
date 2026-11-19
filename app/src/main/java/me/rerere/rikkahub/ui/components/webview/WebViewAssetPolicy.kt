package me.rerere.rikkahub.ui.components.webview

import java.util.Locale

/** A claimed local origin must never fall through to DNS/network when an asset is missing. */
internal fun localWebViewAssetPath(scheme: String?, port: Int, path: String?): String? {
    if (!scheme.equals("https", ignoreCase = true) || port !in setOf(-1, 443)) return null
    if (path == null || !path.startsWith("/assets/")) return null
    val relative = path.removePrefix("/assets/")
    if (relative.isEmpty() || relative.contains('\\') || relative.any { it.code < 32 || it.code == 127 }) return null
    if (relative.split('/').any { it == "." || it == ".." || it.isEmpty() }) return null
    return relative
}

internal fun webViewAssetMimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
    "js", "mjs" -> "text/javascript"
    "css" -> "text/css"
    "html", "htm" -> "text/html"
    "json", "geojson" -> "application/json"
    "wasm" -> "application/wasm"
    "svg" -> "image/svg+xml"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "woff2" -> "font/woff2"
    "woff" -> "font/woff"
    "ttf" -> "font/ttf"
    else -> "application/octet-stream"
}
