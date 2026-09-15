package me.rerere.rikkahub.ui.components.webview

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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

    fun reset() { minimumPageScale = Float.POSITIVE_INFINITY; lockedOwner = WebViewGestureOwner.UNDECIDED }

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


private class RenderingWebView(context: Context) : WebView(context) {
    val loads = WebViewLoadTracker()
    val gesture = ParentVerticalScrollTouchListener(ViewConfiguration.get(context).scaledTouchSlop.toFloat())
    var injectedNames: Set<String> = emptySet()
    private var injectedObjects: Map<String, Any> = emptyMap()
    var released = false

    @SuppressLint("JavascriptInterface")
    fun updateInterfaces(interfaces: Map<String, Any>) {
        if (interfaces == injectedObjects) return
        (injectedNames - interfaces.keys).forEach(::removeJavascriptInterface)
        interfaces.forEach { (name, instance) -> addJavascriptInterface(instance, name) }
        injectedNames = interfaces.keys.toSet()
        injectedObjects = interfaces.toMap()
    }

    fun release() {
        if (released) return
        released = true
        setOnTouchListener(null)
        runCatching { stopLoading() }
        injectedNames.forEach { name -> runCatching { removeJavascriptInterface(name) } }
        injectedNames = emptySet()
        injectedObjects = emptyMap()
        runCatching { webChromeClient = null }
        runCatching { webViewClient = WebViewClient() }
        runCatching { removeAllViews() }
        runCatching { destroy() }
    }
}

/** Keep the Compose layout node stable. WebView attachment/destruction must not
 * mutate the view tree while Compose is placing or releasing an AndroidView. */
private class RenderingWebViewHost(context: Context) : FrameLayout(context) {
    fun configureFocus(inlinePreview: Boolean) {
        // Prevent native rootViewRequestFocus from re-entering LazyColumn subcomposition
        // while AndroidViewHolder is removed. onRelease is too late to clear focus.
        // Touch, zoom and links still work; HTML form editing remains available fullscreen.
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = if (inlinePreview) FOCUS_BLOCK_DESCENDANTS else FOCUS_AFTER_DESCENDANTS
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var latestUpdate: (RenderingWebViewHost.() -> Unit)? = null
    private var disposed = false
    var renderer: RenderingWebView? = null
    var releaseRenderer: (RenderingWebView) -> Unit = { it.release() }

    fun updateLater(update: RenderingWebViewHost.() -> Unit) {
        if (disposed) return
        latestUpdate = update
        // Coalesce updates without moving the scheduled work to the back of
        // the main queue on every recomposition/progress callback.
        if (pending == null) pending = Runnable {
            pending = null
            val action = latestUpdate
            latestUpdate = null
            if (!disposed) action?.invoke(this)
        }.also(mainHandler::post)
    }

    fun removeRenderer() {
        renderer?.let { view ->
            renderer = null
            removeView(view)
            releaseRenderer(view)
        }
    }

    fun dispose() {
        disposed = true
        pending?.let(mainHandler::removeCallbacks)
        pending = null
        latestUpdate = null
        mainHandler.post { removeRenderer() }
    }
}

internal class MyWebChromeClient(private val state: WebViewState) : WebChromeClient() {
    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        if (state.webView === view) {
            state.loadingProgress = (newProgress / 100f).coerceIn(0f, 1f)
            if (newProgress >= 100) state.isLoading = false
        }
    }
    override fun onReceivedTitle(view: WebView?, title: String?) {
        if (state.webView === view) state.pageTitle = title
    }
    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
        state.pushConsoleMessage(message)
        if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Log.w(TAG, "Renderer: ${message.message()}")
        return true
    }
}

internal class MyWebViewClient(private val state: WebViewState) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        WebViewLocalAssets.intercept(view.context.applicationContext, request.url) ?: super.shouldInterceptRequest(view, request)

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        if (state.webView !== view) return
        (view as? RenderingWebView)?.gesture?.reset()
        state.isLoading = true
        state.loadGeneration++
        state.loadWarning = null
        state.loadingProgress = 0f
        state.currentUrl = url
    }
    override fun onPageFinished(view: WebView?, url: String?) {
        if (view == null || state.webView !== view) return
        state.isLoading = false
        state.loadingProgress = 1f
        state.pageTitle = view.title
        state.currentUrl = url
        state.canGoBack = view.canGoBack()
        state.canGoForward = view.canGoForward()
        if (state.restoreContent == state.content) {
            val x = state.restoreX
            val y = state.restoreY
            view.post {
                if (state.webView === view) view.scrollTo(x, y)
            }
            state.restoreContent = null
        }
    }
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame && state.webView === view) {
            state.isLoading = false
            state.error = "页面加载失败（${error.errorCode}），可重试或返回查看源码。"
        }
    }
    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (request.isForMainFrame && state.webView === view) {
            state.isLoading = false
            state.error = "页面加载失败：HTTP ${response.statusCode}"
        }
    }
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        if (state.webView === view) {
            state.webView = null
            state.isLoading = false
            state.error = "图形渲染进程已退出，点击重试重新加载。"
        }
        (view.parent as? ViewGroup)?.removeView(view)
        (view as? RenderingWebView)?.release()
        return true
    }
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
    val chrome = remember(state) { MyWebChromeClient(state) }
    val client = remember(state) { MyWebViewClient(state) }
    val hostView = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val loadingView = state.webView
    val loadGeneration = state.loadGeneration
    var showLoadingIndicator by remember(state) { mutableStateOf(false) }
    LaunchedEffect(loadingView, loadGeneration, state.isLoading) {
        showLoadingIndicator = false
        if (state.isLoading) {
            kotlinx.coroutines.delay(180)
            showLoadingIndicator = true
        }
    }
    LaunchedEffect(loadingView, loadGeneration, state.isLoading) {
        if (loadingView == null || !state.isLoading) return@LaunchedEffect
        kotlinx.coroutines.delay(WEB_PAGE_LOAD_TIMEOUT_MS)
        if (shouldFinishStalledLoad(state.webView === loadingView, state.loadGeneration == loadGeneration, state.isLoading)) {
            loadingView.stopLoading()
            state.isLoading = false
            state.loadingProgress = 1f
            state.loadWarning = "部分网页资源加载超时，已保留当前内容。可重试或查看源码。"
        }
    }
    val exportTracker = me.rerere.rikkahub.ui.components.ui.LocalExportRenderTracker.current
    if (exportTracker != null) {
        val exportToken = remember(state) { Any() }
        DisposableEffect(exportTracker, state) {
            exportTracker.begin(exportToken)
            onDispose { exportTracker.complete(exportToken) }
        }
        LaunchedEffect(exportTracker, state) {
            while (true) {
                state.error?.let { exportTracker.fail(exportToken, it); return@LaunchedEffect }
                val view = state.webView
                if (view != null && state.loadingProgress >= 1f && !state.isLoading) {
                    val status = kotlinx.coroutines.suspendCancellableCoroutine<String> { continuation ->
                        view.evaluateJavascript("""(function() {
                            if (window.__rikkaRenderStatus === 'error') return 'error';
                            if (document.readyState !== 'complete' || window.__rikkaRenderStatus === 'loading') return 'loading';
                            if (document.fonts && document.fonts.status !== 'loaded') return 'loading';
                            if (Array.from(document.images).some(function(image) { return !image.complete; })) return 'loading';
                            document.querySelectorAll('svg').forEach(function(svg) { if (svg.pauseAnimations) svg.pauseAnimations(); });
                            if (document.getAnimations) document.getAnimations().forEach(function(animation) { animation.pause(); });
                            return 'ready';
                        })()""") { result -> if (continuation.isActive) continuation.resumeWith(Result.success(result)) }
                    }
                    if (status == "\"error\"") {
                        exportTracker.fail(exportToken, "图形渲染失败，请检查图形源码")
                        return@LaunchedEffect
                    }
                    if (status == "\"ready\"") {
                        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                            view.postVisualStateCallback(0L, object : WebView.VisualStateCallback() {
                                override fun onComplete(requestId: Long) { if (continuation.isActive) continuation.resumeWith(Result.success(Unit)) }
                            })
                        }
                        exportTracker.complete(exportToken)
                        return@LaunchedEffect
                    }
                }
                kotlinx.coroutines.delay(32)
            }
        }
    }
    var nearViewport by remember(state, deferUntilVisible) { mutableStateOf(!deferUntilVisible) }
    var inViewport by remember(state) { mutableStateOf(false) }
    var promoted by remember(state) { mutableStateOf<Long?>(null) }
    val budgetId = remember(state) { InlineWebViewBudget.allocate() }
    DisposableEffect(budgetId, deferUntilVisible, nearViewport, inViewport, promoted, state.error) {
        if (deferUntilVisible && nearViewport && state.error == null) InlineWebViewBudget.priorities[budgetId] = promoted ?: if (inViewport) 0L else 1L
        onDispose { InlineWebViewBudget.priorities.remove(budgetId) }
    }
    DisposableEffect(lifecycle, state) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> state.webView?.onResume()
                Lifecycle.Event.ON_PAUSE -> state.webView?.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Box(modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
        if (deferUntilVisible) {
            val bounds = coordinates.boundsInWindow(clipBounds = false)
            val height = hostView.height.toFloat().coerceAtLeast(1f)
            val preload = height * .5f
            nearViewport = bounds.width > 0 && bounds.bottom >= -preload && bounds.top <= height + preload
            inViewport = bounds.width > 0 && bounds.bottom >= 0 && bounds.top <= height
            if (!nearViewport) promoted = null
        }
    }) {
        val error = state.error
        val admitted = !deferUntilVisible || InlineWebViewBudget.allows(budgetId)
        val active = nearViewport && admitted && error == null
        key(state) {
            AndroidView(
                factory = { context ->
                    RenderingWebViewHost(context).apply {
                        configureFocus(deferUntilVisible || preferParentVerticalScroll)
                        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                        releaseRenderer = { view ->
                    if (state.webView === view) {
                        if (!view.released) {
                            state.restoreX = view.scrollX
                            state.restoreY = view.scrollY
                            state.restoreContent = view.loads.content
                        }
                        state.webView = null
                    }
                    view.release()
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { it.dispose() },
                update = { host ->
                    // Read snapshot state here so the update is observed, then
                    // apply it on the next main-loop turn, outside layout.
                    val content = state.content
                    val forceReload = state.forceReload
                    val interfaces = state.interfaces
                    val javaScriptEnabled = state.javaScriptEnabled
                    host.updateLater {
                    configureFocus(deferUntilVisible || preferParentVerticalScroll)
                    if (!active) { removeRenderer(); return@updateLater }
                    if (renderer?.released == true) removeRenderer()
                    val view = renderer ?: RenderingWebView(context).also { created ->
                        renderer = created
                        created.settings.domStorageEnabled = true
                        created.settings.allowContentAccess = true
                        if (exportTracker != null) created.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                        created.setBackgroundColor(if (transparentBackground) Color.TRANSPARENT else Color.WHITE)
                        state.webView = created
                        addView(created, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                        onCreated(created)
                        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) created.onPause()
                    }
                    if (!view.released) {
                        state.webView = view
                        view.webChromeClient = chrome
                        view.webViewClient = client
                        view.setBackgroundColor(if (transparentBackground) Color.TRANSPARENT else Color.WHITE)
                        view.setOnTouchListener(if (preferParentVerticalScroll) view.gesture else null)
                        view.updateInterfaces(interfaces)
                        view.settings.javaScriptEnabled = javaScriptEnabled
                        view.settings.apply(state.settings)
                        view.settings.setSupportZoom(true)
                        view.settings.builtInZoomControls = true
                        view.settings.displayZoomControls = false
                        view.settings.useWideViewPort = true
                        view.settings.loadWithOverviewMode = true
                        if (view.loads.needsLoad(content, forceReload)) {
                            view.gesture.reset()
                            if (state.restoreContent != content) state.restoreContent = null
                            state.isLoading = true
                            state.loadWarning = null
                            state.loadingProgress = 0f
                            when (content) {
                                is WebContent.Data -> view.loadDataWithBaseURL(content.baseUrl, content.data, content.mimeType, content.encoding, content.historyUrl)
                                is WebContent.Url -> if (content == view.loads.content && forceReload) view.reload() else view.loadUrl(content.url, content.additionalHttpHeaders)
                                WebContent.NavigatorOnly -> if (forceReload) view.reload() else state.isLoading = false
                            }
                            view.loads.loaded(content)
                            state.forceReload = false
                        }
                        onUpdated(view)
                    }
                    }
                },
            )
        }
        if (nearViewport && error != null) {
            Column { Text(error); TextButton(onClick = state::reload) { Text("重试") } }
        } else if (nearViewport && !admitted) {
            TextButton(onClick = { promoted = InlineWebViewBudget.promote() }) { Text("加载图形") }
        } else if (active && state.isLoading && showLoadingIndicator) LinearProgressIndicator(progress = { state.loadingProgress }, modifier = Modifier.fillMaxWidth())
        if (active && state.loadWarning != null) {
            androidx.compose.material3.Surface(modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter), tonalElevation = androidx.compose.ui.unit.Dp(2f)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(state.loadWarning.orEmpty(), Modifier.weight(1f), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    TextButton(onClick = state::reload) { Text("重试") }
                }
            }
        }
    }
}

sealed class WebContent {
    data class Url(val url: String, val additionalHttpHeaders: Map<String, String> = emptyMap(), val clearHistory: Boolean = false) : WebContent()
    data class Data(val data: String, val baseUrl: String? = null, val encoding: String = "utf-8", val mimeType: String? = null, val historyUrl: String? = null) : WebContent()
    data object NavigatorOnly : WebContent()
}

@Stable
class WebViewState(
    initialContent: WebContent = WebContent.NavigatorOnly,
    interfaces: Map<String, Any> = emptyMap(),
    val settings: WebSettings.() -> Unit = {},
) {
    var content: WebContent by mutableStateOf(initialContent)
    var interfaces: Map<String, Any> by mutableStateOf(interfaces)
        internal set
    internal var forceReload by mutableStateOf(false)
    internal var loadGeneration by mutableIntStateOf(0)
    var loadWarning: String? by mutableStateOf(null)
        internal set
    internal var restoreContent: WebContent? = null
    internal var restoreX = 0
    internal var restoreY = 0
    var error: String? by mutableStateOf(null)
        internal set
    var isLoading by mutableStateOf(false)
        internal set
    var loadingProgress by mutableFloatStateOf(0f)
        internal set
    var pageTitle: String? by mutableStateOf(null)
        internal set
    var currentUrl: String? by mutableStateOf(null)
        internal set
    var canGoBack by mutableStateOf(false)
        internal set
    var canGoForward by mutableStateOf(false)
        internal set
    var consoleMessages: List<ConsoleMessage> by mutableStateOf(emptyList())
        internal set
    var javaScriptEnabled by mutableStateOf(true)
    internal var webView: WebView? by mutableStateOf(null)
    fun loadUrl(url: String, additionalHttpHeaders: Map<String, String> = emptyMap()) {
        forceReload = content == WebContent.Url(url, additionalHttpHeaders)
        error = null
        content = WebContent.Url(url, additionalHttpHeaders)
    }
    fun loadData(data: String, baseUrl: String? = null, encoding: String = "utf-8", mimeType: String? = null, historyUrl: String? = null) {
        error = null
        content = WebContent.Data(data, baseUrl, encoding, mimeType, historyUrl)
    }
    fun goBack() { webView?.goBack() }
    fun goForward() { webView?.goForward() }
    fun reload() { error = null; loadWarning = null; forceReload = true }
    fun stopLoading() { webView?.stopLoading(); isLoading = false }
    fun clearHistory() { webView?.clearHistory() }
    fun pushConsoleMessage(message: ConsoleMessage) { consoleMessages = (consoleMessages + message).takeLast(64) }
}

@Composable
fun rememberWebViewState(
    url: String = "about:blank",
    additionalHttpHeaders: Map<String, String> = emptyMap(),
    interfaces: Map<String, Any> = emptyMap(),
    settings: WebSettings.() -> Unit = {},
): WebViewState {
    val currentSettings by rememberUpdatedState(settings)
    val state = remember(url, additionalHttpHeaders) {
        WebViewState(WebContent.Url(url, additionalHttpHeaders), interfaces, settings = { currentSettings(this) })
    }
    SideEffect { state.interfaces = interfaces }
    return state
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
): WebViewState {
    val currentSettings by rememberUpdatedState(settings)
    val state = remember(baseUrl, encoding, mimeType, historyUrl) {
        WebViewState(WebContent.Data(data, baseUrl, encoding, mimeType, historyUrl), interfaces, settings = { currentSettings(this) })
    }
    SideEffect {
        val next = WebContent.Data(data, baseUrl, encoding, mimeType, historyUrl)
        if (state.content != next) { state.content = next; state.error = null }
        state.interfaces = interfaces
    }
    return state
}
