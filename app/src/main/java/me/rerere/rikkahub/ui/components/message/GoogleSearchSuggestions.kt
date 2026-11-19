package me.rerere.rikkahub.ui.components.message

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import me.rerere.ai.ui.UIMessageAnnotation
import java.net.URLEncoder

/** Render Google's returned search entry point without scripts, local access or a JS bridge. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun GoogleSearchSuggestions(annotation: UIMessageAnnotation.GoogleSearchSuggestions) {
    val handler = LocalUriHandler.current
    val currentHandler by rememberUpdatedState(handler)
    if (annotation.html.isNotBlank()) {
        var height by remember(annotation.html) { mutableIntStateOf(88) }
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(height.dp),
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.domStorageEnabled = false
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val uri = request.url
                            if (request.isForMainFrame && uri.scheme in setOf("http", "https")) {
                                currentHandler.openUri(uri.toString())
                            }
                            return true
                        }
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                            if (request.url.scheme in setOf("https", "data")) null else
                                WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(byteArrayOf()))

                        override fun onPageFinished(view: WebView, url: String?) {
                            view.post { height = view.contentHeight.coerceIn(64, 240) }
                        }
                    }
                }
            },
            update = { view ->
                if (view.tag != annotation.html) {
                    view.tag = annotation.html
                    val policy = "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src https: data:; style-src 'unsafe-inline';\">"
                    view.loadDataWithBaseURL("https://www.google.com/", policy + annotation.html, "text/html", "UTF-8", null)
                }
            },
            onRelease = { it.stopLoading(); it.destroy() },
        )
    } else {
        // Some compatible endpoints return queries but omit Google's HTML entry point.
        Column {
            Text("Google 搜索")
            (annotation.webQueries.map { it to false } + annotation.imageQueries.map { it to true }).forEach { (query, image) ->
                TextButton(onClick = {
                    val encoded = URLEncoder.encode(query, "UTF-8")
                    handler.openUri("https://www.google.com/search?q=$encoded" + if (image) "&udm=2" else "")
                }) { Text(query) }
            }
        }
    }
}

internal fun googleUrlRetrievalLabel(status: String): String = when (status) {
    "URL_RETRIEVAL_STATUS_SUCCESS" -> "URL 内容已读取"
    "URL_RETRIEVAL_STATUS_ERROR" -> "URL 读取失败"
    "URL_RETRIEVAL_STATUS_PAYWALL" -> "URL 内容受付费墙限制"
    "URL_RETRIEVAL_STATUS_UNSAFE" -> "URL 被安全检查拦截"
    else -> "URL 状态：$status"
}
