package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.rerere.rikkahub.ui.components.ui.LocalExportContext
import me.rerere.rikkahub.ui.components.webview.WEB_VIEW_ASSET_URL
import me.rerere.rikkahub.ui.components.webview.WEB_VIEW_BASE_URL
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import java.util.Base64

internal const val MAX_TERMINAL_PREVIEW_CHARS = 128 * 1024

internal fun isTerminalFence(language: String): Boolean =
    me.rerere.rikkahub.data.render.RendererCatalog.find(normalizeCodeFenceLanguage(language))?.id == "terminal"

internal fun canRenderTerminalPreview(language: String, code: String, complete: Boolean): Boolean =
    complete && isTerminalFence(language) && code.length <= MAX_TERMINAL_PREVIEW_CHARS

/** Display-only VT subset: no OSC links/clipboard/title, DCS images, device queries or modes.
 * Parse linearly and drop unterminated strings rather than exposing their payload as text.
 * The original message remains untouched for source view, copying and export.
 */
internal fun terminalDisplayText(source: String, keepAnsi: Boolean = true): String {
    val input = source.replace("\\x1b", "\u001b", ignoreCase = true)
        .replace("\\u001b", "\u001b", ignoreCase = true)
    val result = StringBuilder(input.length)
    var i = 0
    fun skipString(osc: Boolean) {
        while (i < input.length) {
            val c = input[i++]
            if (c == '\u009c' || osc && c == '\u0007') return
            if (c == '\u001b' && input.getOrNull(i) == '\\') { i++; return }
        }
    }
    fun csi() {
        val start = i
        while (i < input.length && input[i].code in 0x20..0x3f) i++
        if (i >= input.length) return
        val final = input[i]
        if (final.code !in 0x40..0x7e) return
        i++
        val params = input.substring(start, i - 1)
        if (keepAnsi && final in "mKJABCDEFGHfdsu" && params.length <= 96 &&
            params.all { it in '0'..'9' || it == ';' || it == ':' }) {
            result.append('\u001b').append('[').append(params).append(final)
        }
    }
    while (i < input.length) {
        when (val c = input[i++]) {
            '\u001b' -> when (input.getOrNull(i++)) {
                '[' -> csi()
                ']' -> skipString(osc = true)
                'P', '_', '^', 'X' -> skipString(osc = false)
                else -> { // Consume an unsupported escape's intermediates and final byte.
                    while (i - 1 < input.length && input[i - 1].code in 0x20..0x2f) i++
                }
            }
            '\u009b' -> csi()
            '\u009d' -> skipString(osc = true)
            '\u0090', '\u0098', '\u009e', '\u009f' -> skipString(osc = false)
            '\n', '\r', '\t', '\b' -> result.append(c)
            else -> if (c.code >= 0x20 && c.code !in 0x7f..0x9f) result.append(c)
        }
    }
    return result.toString()
}

internal fun buildTerminalPreviewHtml(
    code: String,
    dark: Boolean = true,
): String {
    require(code.length <= MAX_TERMINAL_PREVIEW_CHARS) { "Terminal preview source is too large" }
    val encoded = Base64.getEncoder().encodeToString(terminalDisplayText(code).toByteArray(Charsets.UTF_8))
    val assets = "$WEB_VIEW_ASSET_URL/html/renderers"
    val background = if (dark) "#161b22" else "#ffffff"
    val foreground = if (dark) "#e6edf3" else "#24292f"
    return """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <meta name="rikkahub-preview" content="terminal-readonly">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src $assets/xterm-6.0.0.js $assets/xterm-addon-fit-0.11.0.js $assets/terminal-preview.js; style-src 'unsafe-inline' $assets/xterm-6.0.0.css; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'">
        <link rel="stylesheet" href="$assets/xterm-6.0.0.css">
        <style>
            html,body{width:100%;height:100%;margin:0;overflow:hidden;background:$background;color:$foreground}
            #terminal-scroll{width:100%;height:100%;overflow-x:auto;overflow-y:hidden;overscroll-behavior-x:contain;touch-action:pan-y pinch-zoom}
            #terminal{height:100%;min-width:100%;box-sizing:border-box;padding:8px}
            #terminal-fallback{height:100%;overflow:auto;box-sizing:border-box;padding:12px}
            #fallback,#error{margin:0;white-space:pre-wrap;overflow-wrap:anywhere;font:13px monospace}
            #error{color:#e5534b;margin-bottom:8px}
            #terminal-scroll[hidden],#terminal-fallback[hidden]{display:none}
            .xterm-helper-textarea{pointer-events:none!important}
            .xterm .xterm-cursor{visibility:hidden!important}
        </style>
        <script defer src="$assets/xterm-6.0.0.js"></script>
        <script defer src="$assets/xterm-addon-fit-0.11.0.js"></script>
        <script defer src="$assets/terminal-preview.js"></script>
        </head><body data-source="$encoded" data-background="$background" data-foreground="$foreground" data-dark="$dark" data-embedded="true">
        <div id="terminal-scroll"><div id="terminal" role="region" aria-label="只读终端输出"></div></div>
        <div id="terminal-fallback" hidden><pre id="error"></pre><pre id="fallback"></pre></div>
        </body></html>
    """.trimIndent()
}

@Composable
internal fun TerminalCodePreview(html: String, onViewCreated: (android.webkit.WebView) -> Unit) {
    val state = rememberWebViewState(
        data = html, baseUrl = WEB_VIEW_BASE_URL, mimeType = "text/html", encoding = "UTF-8",
        settings = {
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            blockNetworkLoads = true
            loadWithOverviewMode = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        },
    )
    WebView(state = state, onCreated = onViewCreated, deferUntilVisible = !LocalExportContext.current,
        preferParentVerticalScroll = true, modifier = Modifier.fillMaxWidth().height(
            richPreviewHeight(minHeightDp = 220, maxHeightDp = 360, widthFraction = 0.72f)))
}
