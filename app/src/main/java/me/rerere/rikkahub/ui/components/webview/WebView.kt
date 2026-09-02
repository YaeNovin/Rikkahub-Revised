package me.rerere.rikkahub.ui.components.webview

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs
import kotlin.math.min

private const val TAG = "WebView"

internal enum class WebViewGestureOwner {
    UNDECIDED,
    PARENT_VERTICAL,
    WEB_VIEW,
}

internal fun resolveWebViewGestureOwner(
    deltaX: Float,
    deltaY: Float,
    pointerCount: Int,
    touchSlop: Float,
    isZoomed: Boolean = false,
): WebViewGestureOwner = when {
    pointerCount > 1 -> WebViewGestureOwner.WEB_VIEW
    maxOf(abs(deltaX), abs(deltaY)) < touchSlop -> WebViewGestureOwner.UNDECIDED
    isZoomed -> WebViewGestureOwner.WEB_VIEW
    abs(deltaY) > abs(deltaX) -> WebViewGestureOwner.PARENT_VERTICAL
    else -> WebViewGestureOwner.WEB_VIEW
}

private class ParentVerticalScrollTouchListener(
    private val touchSlop: Float,
) : View.OnTouchListener {
    private var downX = 0f
    private var downY = 0f
    private var minimumPageScale = Float.POSITIVE_INFINITY
    private var lockedOwner = WebViewGestureOwner.UNDECIDED

    @Suppress("DEPRECATION")
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lockedOwner = WebViewGestureOwner.UNDECIDED
                (view as? WebView)?.getScale()?.takeIf { it > 0f }?.let {
                    minimumPageScale = min(minimumPageScale, it)
                }
                view.parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                lockedOwner = WebViewGestureOwner.WEB_VIEW
                view.parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                val currentScale = (view as? WebView)?.getScale()?.takeIf { it > 0f }
                val isZoomed = currentScale != null && minimumPageScale.isFinite() &&
                    currentScale > minimumPageScale * 1.02f
                val owner = if (lockedOwner == WebViewGestureOwner.WEB_VIEW) {
                    lockedOwner
                } else {
                    resolveWebViewGestureOwner(
                        deltaX = event.x - downX,
                        deltaY = event.y - downY,
                        pointerCount = event.pointerCount,
                        touchSlop = touchSlop,
                        isZoomed = isZoomed,
                    ).also { resolved ->
                        if (resolved == WebViewGestureOwner.WEB_VIEW) lockedOwner = resolved
                    }
                }
                when (owner) {
                    WebViewGestureOwner.PARENT_VERTICAL ->
                        view.parent?.requestDisallowInterceptTouchEvent(false)

                    WebViewGestureOwner.WEB_VIEW ->
                        view.parent?.requestDisallowInterceptTouchEvent(true)

                    WebViewGestureOwner.UNDECIDED -> Unit
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                lockedOwner = WebViewGestureOwner.UNDECIDED
                view.parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return false
    }
}

internal class MyWebChromeClient(private val state: WebViewState) : WebChromeClient() {
    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        state.loadingProgress = newProgress / 100f
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        super.onReceivedTitle(view, title)
        state.pageTitle = title
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        state.pushConsoleMessage(consoleMessage)
        if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR || consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.WARNING) {
            Log.e(
                TAG,
                "onConsoleMessage:  ${consoleMessage.message()}  ${consoleMessage.lineNumber()}  ${consoleMessage.sourceId()}"
            )
        }
        return super.onConsoleMessage(consoleMessage);
    }
}

internal class MyWebViewClient(private val state: WebViewState) : WebViewClient() {
    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        return WebViewLocalAssets.intercept(view.context.applicationContext, request.url)
            ?: super.shouldInterceptRequest(view, request)
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        state.isLoading = true
        state.currentUrl = url // Update current URL
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        state.isLoading = false
        state.loadingProgress = 0f // Reset progress when finished
        state.pageTitle = view?.title // Update title
        state.canGoBack = view?.canGoBack() == true
        state.canGoForward = view?.canGoForward() == true
    }
}

private fun WebView.resetState(
    interfaces: Map<String, Any>,
    clearClients: Boolean = false,
) {
    stopLoading()
    interfaces.forEach { (name, _) ->
        removeJavascriptInterface(name)
    }
    if (clearClients) {
        webChromeClient = null
        webViewClient = WebViewClient()
    }
}

private fun WebView.release(interfaces: Map<String, Any>) {
    resetState(interfaces, clearClients = true)
    loadUrl("about:blank")
    clearHistory()
    removeAllViews()
    destroy()
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun WebView(
    state: WebViewState,
    modifier: Modifier = Modifier,
    deferUntilVisible: Boolean = false,
    preferParentVerticalScroll: Boolean = false,
    transparentBackground: Boolean = false,
    onCreated: (WebView) -> Unit = {},
    onUpdated: (WebView) -> Unit = {},
) {
    // Remember the clients based on the state
    val webChromeClient = remember { MyWebChromeClient(state) }
    val webViewClient = remember { MyWebViewClient(state) }
    val hostView = LocalView.current
    var nearViewport by remember(state, deferUntilVisible) {
        mutableStateOf(!deferUntilVisible)
    }

    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            if (deferUntilVisible) {
                val bounds = coordinates.boundsInWindow()
                val viewportHeight = hostView.height.toFloat().coerceAtLeast(1f)
                val preloadDistance = viewportHeight * 0.75f
                nearViewport = bounds.bottom >= -preloadDistance &&
                    bounds.top <= viewportHeight + preloadDistance
            }
        }
    ) {
        if (!nearViewport) return@Box

        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    layoutParams = LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT
                    )

                    state.webView = this // Assign the WebView instance to the state

                    if (transparentBackground) {
                        setBackgroundColor(Color.TRANSPARENT)
                    }
                    onCreated(this)
                    if (preferParentVerticalScroll) {
                        setOnTouchListener(
                            ParentVerticalScrollTouchListener(
                                touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
                            )
                        )
                    }

                    settings.javaScriptEnabled = true // Enable JavaScript
                    settings.domStorageEnabled = true
                    settings.allowContentAccess = true
                    settings.apply(state.settings)
                    // Keep native WebView gestures available for every preview:
                    // pinch zoom, zoom-out and two-finger panning must not depend
                    // on each individual renderer remembering these flags.
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true

                    // Use the created clients
                    this.webChromeClient = webChromeClient
                    this.webViewClient = webViewClient

                    state.interfaces.forEach { (name, obj) ->
                        addJavascriptInterface(obj, name)
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = {
                if (state.webView === it) {
                    state.webView = null
                }
                it.release(state.interfaces)
                Log.d(TAG, "AndroidView: Releasing WebView")
            },
            update = { webView ->
                state.webView = webView
                if (transparentBackground) {
                    webView.setBackgroundColor(Color.TRANSPARENT)
                }
                state.interfaces.forEach { (name, obj) ->
                    webView.addJavascriptInterface(obj, name)
                }
                Log.d(TAG, "AndroidView: Updating WebView")
                webView.webChromeClient = webChromeClient
                webView.webViewClient = webViewClient

                // Update settings that might change
                webView.settings.javaScriptEnabled = state.javaScriptEnabled
                webView.settings.apply(state.settings)
                webView.settings.setSupportZoom(true)
                webView.settings.builtInZoomControls = true
                webView.settings.displayZoomControls = false
                webView.settings.useWideViewPort = true
                webView.settings.loadWithOverviewMode = true

                when (val content = state.content) {
                    is WebContent.Url -> {
                        val url = content.url
                        // Only load new URL if it's different from the current one or if the state forces reload
                        // Also check if the webView's url is null or blank, which might happen initially
                        val currentWebViewUrl = webView.url
                        if (url.isNotEmpty() && (currentWebViewUrl.isNullOrBlank() || url != currentWebViewUrl || state.forceReload)) {
                            webView.loadUrl(content.url, content.additionalHttpHeaders)
                            state.forceReload = false // Reset force reload flag
                        }
                    }

                    is WebContent.Data -> {
                        if (content != state.lastLoadedData || state.forceReload) {
                            webView.loadDataWithBaseURL(
                                content.baseUrl,
                                content.data,
                                content.mimeType,
                                content.encoding,
                                content.historyUrl
                            )
                            state.lastLoadedData = content
                            state.forceReload = false
                        }
                    }

                    WebContent.NavigatorOnly -> {
                        // NO-OP: State changes related to navigation are handled by the methods in WebViewState
                    }
                }
                onUpdated(webView)
            }
        )

        // Loading Progress Indicator
        if (state.isLoading) {
            LinearProgressIndicator(
                progress = { state.loadingProgress },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// --- State and Content Definition ---
sealed class WebContent {
    data class Url(
        val url: String,
        val additionalHttpHeaders: Map<String, String> = emptyMap(),
        val clearHistory: Boolean = false
    ) : WebContent()

    data class Data(
        val data: String,
        val baseUrl: String? = null,
        val encoding: String = "utf-8",
        val mimeType: String? = null,
        val historyUrl: String? = null
    ) : WebContent()

    data object NavigatorOnly : WebContent()
}

@Stable // Mark as Stable for better Compose performance
class WebViewState(
    initialContent: WebContent = WebContent.NavigatorOnly,
    val interfaces: Map<String, Any> = emptyMap(),
    val settings: WebSettings.() -> Unit = {}
) {
    // --- Content State ---
    var content: WebContent by mutableStateOf(initialContent)
    internal var forceReload: Boolean by mutableStateOf(false) // Internal state to force URL reload if needed
    internal var lastLoadedData: WebContent.Data? = null

    // --- Loading State ---
    var isLoading: Boolean by mutableStateOf(false)
        internal set // Only WebViewClients should modify this
    var loadingProgress: Float by mutableFloatStateOf(0f)
        internal set

    // --- Page Information ---
    var pageTitle: String? by mutableStateOf(null)
        internal set
    var currentUrl: String? by mutableStateOf(null)
        internal set

    // --- Navigation State ---
    var canGoBack: Boolean by mutableStateOf(false)
        internal set
    var canGoForward: Boolean by mutableStateOf(false)
        internal set

    // --- Console Message ---
    var consoleMessages: List<ConsoleMessage> by mutableStateOf(emptyList())
        internal set

    // --- Settings ---
    var javaScriptEnabled: Boolean by mutableStateOf(true) // Example setting

    // --- WebView Instance ---
    // Hold the WebView instance internally to perform actions.
    // Be cautious with this reference, ensure it doesn't leak context.
    internal var webView: WebView? by mutableStateOf(null)

    // --- Public Actions ---

    fun loadUrl(
        url: String,
        additionalHttpHeaders: Map<String, String> = emptyMap()
    ) {
        // Determine if reload is needed: same URL or explicit force flag set elsewhere
        forceReload =
            (content is WebContent.Url && (content as WebContent.Url).url == url) || forceReload
        content = WebContent.Url(url, additionalHttpHeaders)
    }

    fun loadData(
        data: String,
        baseUrl: String? = null,
        encoding: String = "utf-8",
        mimeType: String? = null,
        historyUrl: String? = null
    ) {
        content = WebContent.Data(data, baseUrl, encoding, mimeType, historyUrl)
    }

    // --- Navigation Methods ---
    fun goBack() {
        webView?.goBack()
    }

    fun goForward() {
        webView?.goForward()
    }

    fun reload() {
        // Set forceReload flag for URL content type to ensure `update` block reloads
        forceReload = true
        // Trigger recomposition/update by changing the content reference slightly,
        // even if the URL is the same. Assigning the same Url object might not trigger update.
        // Or simply call webView?.reload() directly.
        webView?.reload()
        // If content is Data, reloading might mean re-setting the data.
        if (content is WebContent.Data) {
            // Re-assign to trigger update block if necessary
            content = (content as WebContent.Data).copy()
        }
    }

    fun stopLoading() {
        webView?.stopLoading()
    }

    fun clearHistory() {
        webView?.clearHistory()
    }

    fun pushConsoleMessage(message: ConsoleMessage) {
        consoleMessages = consoleMessages + message
        if (consoleMessages.size > 64) { // Limit to 64 messages
            consoleMessages = consoleMessages.takeLast(64)
        }
    }
}

@Composable
fun rememberWebViewState(
    url: String = "about:blank",
    additionalHttpHeaders: Map<String, String> = emptyMap(),
    interfaces: Map<String, Any> = emptyMap(),
    settings: WebSettings.() -> Unit = {},
) = remember(url, additionalHttpHeaders) { // Use keys for better recomposition control
    WebViewState(
        initialContent = WebContent.Url(url, additionalHttpHeaders),
        interfaces = interfaces,
        settings = settings
    )
}

@Composable
fun rememberWebViewState(
    data: String,
    baseUrl: String? = null,
    encoding: String = "utf-8",
    mimeType: String? = null,
    historyUrl: String? = null,
    interfaces: Map<String, Any> = emptyMap(),
    settings: WebSettings.() -> Unit = {},
) = remember(data, baseUrl, encoding, mimeType, historyUrl) { // Use keys
    WebViewState(
        initialContent = WebContent.Data(data, baseUrl, encoding, mimeType, historyUrl),
        interfaces = interfaces,
        settings = settings
    )
}
