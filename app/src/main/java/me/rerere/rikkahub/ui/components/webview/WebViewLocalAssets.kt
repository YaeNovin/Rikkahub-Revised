package me.rerere.rikkahub.ui.components.webview

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse

/**
 * 应用内 WebView 使用的虚拟域名，用于给 loadDataWithBaseURL 提供一个稳定的 origin。
 */
const val WEB_VIEW_BASE_URL = "https://rikkahub.local"

/**
 * 虚拟域名下的静态资源前缀，会被映射到 app 的 assets 目录。
 *
 * 例如 `https://rikkahub.local/assets/html/mermaid.min.js` 对应 `assets/html/mermaid.min.js`，
 * 这样 mermaid/katex 之类的库可以本地加载，而不用从 CDN 下载（首次加载可能需要几十秒）。
 */
const val WEB_VIEW_ASSET_URL = "$WEB_VIEW_BASE_URL/assets"

private const val ASSET_HOST = "rikkahub.local"

internal object WebViewLocalAssets {
    fun intercept(context: Context, uri: Uri): WebResourceResponse? {
        if (!uri.host.equals(ASSET_HOST, ignoreCase = true)) return null
        val assetPath = localWebViewAssetPath(uri.scheme, uri.port, uri.path) ?: return notFound()
        val mimeType = webViewAssetMimeType(assetPath)
        return runCatching {
            WebResourceResponse(
                mimeType,
                if (mimeType.startsWith("text/") || mimeType.endsWith("json") || mimeType.endsWith("xml")) "UTF-8" else null,
                context.assets.open(assetPath)
            )
        }.getOrElse { notFound() }
    }

    private fun notFound() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(),
        "Local rendering resource unavailable".byteInputStream())
}
