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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
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

private class ParentVerticalScrollCoordinator(
    private val touchSlop: Float,
) {
    private var downX = 0f
    private var downY = 0f
    private var minimumPageScale = Float.POSITIVE_INFINITY
    private var lockedOwner = WebViewGestureOwner.UNDECIDED

    fun reset() { minimumPageScale = Float.POSITIVE_INFINITY; lockedOwner = WebViewGestureOwner.UNDECIDED }

    @Suppress("DEPRECATION")
    fun onMotion(view: View, event: MotionEvent) {
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
    }
}


private class RenderingWebView(context: Context) : WebView(context) {
    val loads = WebViewLoadTracker()
    val gesture = ParentVerticalScrollCoordinator(ViewConfiguration.get(context).scaledTouchSlop.toFloat())
    var preferParentVerticalScroll: Boolean = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (preferParentVerticalScroll) gesture.onMotion(this, event)
        // Only coordinate interception. Native WebView owns clicks, accessibility and links.
        return super.dispatchTouchEvent(event)
    }
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
        preferParentVerticalScroll = false
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
    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
        if (state.content !is WebContent.Data) return super.onJsAlert(view, url, message, result)
        result.cancel(); return true
    }
    override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
        if (state.content !is WebContent.Data) return super.onJsConfirm(view, url, message, result)
        result.cancel(); return true
    }
    override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
        if (state.content !is WebContent.Data) return super.onJsPrompt(view, url, message, defaultValue, result)
        result.cancel(); return true
    }
    override fun onPermissionRequest(request: PermissionRequest) {
        // Renderers do not need camera, microphone or protected media access.
        request.deny()
    }

    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: android.os.Message?,
    ): Boolean = false

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
        if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR && state.content is WebContent.Data) {
            me.rerere.rikkahub.data.render.RenderDiagnostics.report("WebView", "javascript",
                "line=${message.lineNumber()}\n${message.message()}")
        }
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
        if (state.webView === view && state.content is WebContent.Data) {
            me.rerere.rikkahub.data.render.RenderDiagnostics.report("WebView", "resource",
                "code=${error.errorCode}; mainFrame=${request.isForMainFrame}; ${error.description}")
        }
        if (request.isForMainFrame && state.webView === view) {
            state.isLoading = false
            state.error = "页面加载失败（${error.errorCode}），可重试或返回查看源码。"
        }
    }
    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (state.webView === view && state.content is WebContent.Data) {
            me.rerere.rikkahub.data.render.RenderDiagnostics.report("WebView", "http",
                "HTTP ${response.statusCode}; mainFrame=${request.isForMainFrame}")
        }
        if (request.isForMainFrame && state.webView === view) {
            state.isLoading = false
            state.error = "页面加载失败：HTTP ${response.statusCode}"
        }
    }
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        me.rerere.rikkahub.data.render.RenderDiagnostics.report("WebView", "process_exit",
            "didCrash=${detail.didCrash()}; priority=${detail.rendererPriorityAtExit()}")
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
    nativeZoomEnabled: Boolean = true,
    previewHeight: Dp? = null,
) {
    val chrome = remember(state) { MyWebChromeClient(state) }
    val client = remember(state) { MyWebViewClient(state) }
    val hostView = LocalView.current
    val linkActions by rememberUpdatedState(me.rerere.rikkahub.ui.components.richtext.LocalLinkActionHandler.current)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var hostResumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val loadingView = state.webView
    val loadGeneration = state.loadGeneration
    LaunchedEffect(loadingView, loadGeneration, state.isLoading) {
        if (loadingView == null || state.isLoading || state.content !is WebContent.Data) return@LaunchedEffect
        repeat(30) {
            kotlinx.coroutines.delay(1_000)
            if (state.webView !== loadingView || state.loadGeneration != loadGeneration) return@LaunchedEffect
            val result = kotlinx.coroutines.withTimeoutOrNull(1_500) {
                kotlinx.coroutines.suspendCancellableCoroutine<String> { continuation ->
                    loadingView.evaluateJavascript("""
                        (() => JSON.stringify({status: window.__rikkaRenderStatus || '',
                          renderer: document.querySelector('meta[name=rikkahub-geometry-kind]')?.content || 'WebView',
                          error: window.__rikkaRenderStatus === 'error' ?
                            (document.querySelector('#error, #render-error')?.textContent || 'Render failed').slice(0,4096) : ''}))()
                    """.trimIndent()) { value ->
                        if (continuation.isActive) continuation.resumeWith(Result.success(value))
                    }
                }
            }
            val probe = runCatching {
                Json.parseToJsonElement(Json.parseToJsonElement(result ?: "null").jsonPrimitive.content).jsonObject
            }.getOrNull()
            if (probe?.get("status")?.jsonPrimitive?.content == "error") {
                me.rerere.rikkahub.data.render.RenderDiagnostics.report(
                    probe["renderer"]?.jsonPrimitive?.content.orEmpty(), "runtime",
                    probe["error"]?.jsonPrimitive?.content.orEmpty())
                return@LaunchedEffect
            }
            // Finished/static pages need no recurring polling while the user scrolls.
            if (probe?.get("status")?.jsonPrimitive?.content in setOf("ready", "disposed", "")) return@LaunchedEffect
        }
    }
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
            me.rerere.rikkahub.data.render.RenderDiagnostics.report("WebView", "load_timeout", state.loadWarning.orEmpty())
        }
    }
    val exportTracker = me.rerere.rikkahub.ui.components.ui.LocalExportRenderTracker.current
    val exportDensity = LocalDensity.current
    var exportHeight by remember(state) { mutableStateOf<Dp?>(null) }
    var exportError by remember(state) { mutableStateOf<String?>(null) }
    if (exportTracker != null) {
        val exportToken = remember(state) { Any() }
        DisposableEffect(exportTracker, state) {
            exportTracker.begin(exportToken)
            onDispose { exportTracker.complete(exportToken) }
        }
        LaunchedEffect(exportTracker, state) {
            fun fallback(reason: String) {
                me.rerere.rikkahub.data.render.RenderDiagnostics.report("WebView", "image_export", reason)
                exportError = reason
                exportTracker.warn(exportToken, reason)
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + 25_000
            while (true) {
                state.error?.let { fallback(it); return@LaunchedEffect }
                if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                    fallback("图形加载超时，未能包含预览；原始源码仍保留在对话中")
                    return@LaunchedEffect
                }
                val view = state.webView
                if (view != null && state.loadingProgress >= 1f && !state.isLoading) {
                    val response = kotlinx.coroutines.withTimeoutOrNull(2_000) {
                        kotlinx.coroutines.suspendCancellableCoroutine<String> { continuation ->
                            view.evaluateJavascript(WEBVIEW_EXPORT_PROBE) { result ->
                                if (continuation.isActive) continuation.resumeWith(Result.success(result))
                            }
                        }
                    }
                    val probe = runCatching {
                        Json.parseToJsonElement(Json.parseToJsonElement(response ?: "null").jsonPrimitive.content).jsonObject
                    }.getOrNull()
                    val status = probe?.get("status")?.jsonPrimitive?.content
                    if (status == "error") {
                        fallback(probe?.get("error")?.jsonPrimitive?.content.orEmpty())
                        return@LaunchedEffect
                    }
                    if (status == "ready") {
                        val cssHeight = probe?.get("height")?.jsonPrimitive?.doubleOrNull ?: 0.0
                        val viewport = probe?.get("viewport")?.jsonPrimitive?.doubleOrNull ?: 0.0
                        val naturalPixels = if (viewport > 0) kotlin.math.ceil(cssHeight * view.height / viewport).toInt() else view.height
                        if (previewHeight != null && naturalPixels > view.height + 2) {
                            if (naturalPixels > 24_000) {
                                fallback("图形内容过长，无法完整绘入本页；请单独导出原始图形")
                                return@LaunchedEffect
                            }
                            exportHeight = with(exportDensity) { naturalPixels.toDp() }
                            kotlinx.coroutines.delay(100)
                            continue
                        }
                        val painted = kotlinx.coroutines.withTimeoutOrNull(3_000) {
                            kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { continuation ->
                                view.postVisualStateCallback(0L, object : WebView.VisualStateCallback() {
                                    override fun onComplete(requestId: Long) { if (continuation.isActive) continuation.resumeWith(Result.success(true)) }
                                })
                            }
                        }
                        if (painted != true) { fallback("图形未能完成绘制，原始源码仍保留在对话中"); return@LaunchedEffect }
                        exportTracker.complete(exportToken)
                return@LaunchedEffect
            }
            // Finished/static pages need no recurring polling while the user scrolls.
            if (probe?.get("status")?.jsonPrimitive?.content in setOf("ready", "disposed", "")) return@LaunchedEffect
        }
                kotlinx.coroutines.delay(32)
            }
        }
    }
    var nearViewport by remember(state, deferUntilVisible) { mutableStateOf(!deferUntilVisible) }
    var inViewport by remember(state) { mutableStateOf(false) }
    // Fixed renderers may pause their own timers; no native bridge or global pauseTimers.
    LaunchedEffect(state.webView, state.isLoading, hostResumed, inViewport, deferUntilVisible, exportTracker) {
        if (!state.isLoading) {
            val active = hostResumed && (!deferUntilVisible || inViewport) && exportTracker == null
            state.webView?.notifyRenderEnvironment()
            state.webView?.evaluateJavascript("if (typeof window.rikkaSetHostActive === 'function') window.rikkaSetHostActive($active);", null)
        }
    }
    var promoted by remember(state) { mutableStateOf<Long?>(null) }
    val budgetId = remember(state) { InlineWebViewBudget.allocate() }
    DisposableEffect(budgetId, deferUntilVisible, nearViewport, inViewport, promoted, state.error) {
        if (deferUntilVisible && nearViewport && state.error == null) InlineWebViewBudget.priorities[budgetId] = promoted ?: if (inViewport) 0L else 1L
        onDispose { InlineWebViewBudget.priorities.remove(budgetId) }
    }
    DisposableEffect(lifecycle, state) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { hostResumed = true; state.webView?.onResume() }
                Lifecycle.Event.ON_PAUSE -> { hostResumed = false; state.webView?.onPause() }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val sizedModifier = if (previewHeight != null && !(exportTracker != null && (exportError != null || state.error != null)))
        modifier.height(exportHeight ?: previewHeight) else modifier
    Box(sizedModifier.fillMaxWidth().onGloballyPositioned { coordinates ->
        if (deferUntilVisible) {
            val bounds = coordinates.boundsInWindow(clipBounds = false)
            val height = hostView.height.toFloat().coerceAtLeast(1f)
            val preload = height * .5f
            nearViewport = bounds.width > 0 && bounds.bottom >= -preload && bounds.top <= height + preload
            inViewport = bounds.width > 0 && bounds.bottom >= 0 && bounds.top <= height
            if (!nearViewport) promoted = null
        }
    }) {
        val error = exportError ?: state.error
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
                        created.setLayerType(previewLayerType(exportTracker != null), null)
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
                        view.setOnLongClickListener {
                            val handler = linkActions ?: return@setOnLongClickListener false
                            val hit = view.hitTestResult
                            when (hit.type) {
                                android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                                    hit.extra?.let { handler(me.rerere.rikkahub.ui.components.richtext.LinkTarget(it)) } != null
                                }
                                android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                                    val callback = Handler(Looper.getMainLooper()) { message ->
                                        message.data.getString("url")?.let {
                                            handler(me.rerere.rikkahub.ui.components.richtext.LinkTarget(it, message.data.getString("title").orEmpty()))
                                        }
                                        true
                                    }
                                    view.requestFocusNodeHref(callback.obtainMessage())
                                    true
                                }
                                else -> false
                            }
                        }
                        view.setBackgroundColor(if (transparentBackground) Color.TRANSPARENT else Color.WHITE)
                        view.preferParentVerticalScroll = preferParentVerticalScroll
                        view.updateInterfaces(interfaces)
                        view.settings.javaScriptEnabled = javaScriptEnabled
                        view.settings.allowFileAccess = false
                        view.settings.allowContentAccess = false
                        view.settings.javaScriptCanOpenWindowsAutomatically = false
                        view.settings.setSupportMultipleWindows(false)
                        view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            view.settings.safeBrowsingEnabled = true
                        }
                        view.settings.useWideViewPort = true
                        view.settings.loadWithOverviewMode = true
                        view.settings.apply(state.settings)
                        view.settings.setSupportZoom(nativeZoomEnabled)
                        view.settings.builtInZoomControls = nativeZoomEnabled
                        view.settings.displayZoomControls = false
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
            Column { Text(error); if (exportTracker == null) TextButton(onClick = state::reload) { Text("重试") } }
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
