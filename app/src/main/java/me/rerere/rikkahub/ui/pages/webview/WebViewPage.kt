package me.rerere.rikkahub.ui.pages.webview

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Bug01
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.Refresh01
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import me.rerere.rikkahub.ui.components.ui.AppearanceDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import me.rerere.rikkahub.ui.components.ui.AppearanceModalBottomSheet as ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import me.rerere.rikkahub.ui.components.ui.TopAppBar
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.isGlobalBackgroundActive
import me.rerere.rikkahub.data.datastore.isFullscreenPreviewBackgroundActive
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.components.ui.GlobalAppBackground
import me.rerere.rikkahub.ui.components.ui.LocalGlassBackdrop
import me.rerere.rikkahub.ui.components.ui.LocalAppearanceBackground
import me.rerere.rikkahub.ui.components.ui.AppearanceBackgroundSpec
import me.rerere.rikkahub.ui.context.LocalGlobalBackgroundActive
import me.rerere.rikkahub.ui.context.LocalPageSurfaceStyle
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.LocalBackgroundBaseColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.View
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.installHtmlPreviewDependencies
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.webview.WEB_VIEW_BASE_URL
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.WebViewContentCache
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import org.jsoup.Jsoup

private const val FULLSCREEN_PREVIEW_STYLE_ID = "rikkahub-fullscreen-preview-style"
private const val FULLSCREEN_PREVIEW_BODY_CLASS = "rikkahub-fullscreen-preview"

internal fun prepareFullscreenPreviewHtml(content: String, useGlobalBackground: Boolean = false): String {
    if (content.isBlank()) return content
    val document = Jsoup.parse(content)
    document.outputSettings().prettyPrint(false)
    // Also repair raw HTML already cached by older builds. Typed renderers retain their loaders.
    if (document.getElementById("rikkahub-responsive-preview") != null) {
        installHtmlPreviewDependencies(document)
    }
    document.body().addClass(FULLSCREEN_PREVIEW_BODY_CLASS)
    document.select("html,body").forEach {
        if (useGlobalBackground) it.addClass("rikka-preview-global-background") else it.removeClass("rikka-preview-global-background")
    }
    document.head().select("#$FULLSCREEN_PREVIEW_STYLE_ID,#rikkahub-fullscreen-background-style").remove()
    document.head().appendElement("style")
        .attr("id", FULLSCREEN_PREVIEW_STYLE_ID)
        .append(
            """
                html {
                    width: 100%;
                    height: 100%;
                    margin: 0;
                    overflow: auto !important;
                    touch-action: pan-x pan-y pinch-zoom;
                }
                body.$FULLSCREEN_PREVIEW_BODY_CLASS {
                    box-sizing: border-box;
                    width: 100%;
                    min-width: 100%;
                    min-height: 100%;
                    height: 100%;
                    margin: 0;
                    overflow: auto !important;
                    display: flex !important;
                    flex-direction: column !important;
                    touch-action: pan-x pan-y pinch-zoom;
                    -webkit-overflow-scrolling: touch;
                }
                body.$FULLSCREEN_PREVIEW_BODY_CLASS > :not(script):not(style):not(link):not([hidden]) {
                    flex: 0 0 auto;
                    margin: auto;
                }
                body.$FULLSCREEN_PREVIEW_BODY_CLASS > :not(script):not(style):not(link):not([hidden]) {
                    cursor: auto;
                }
                body.$FULLSCREEN_PREVIEW_BODY_CLASS > :not(script):not(style):not(link):not([hidden]):active {
                    cursor: grabbing;
                }
                #renderer, #diagram-container, #rikkahub-pan-scene {
                    width: 100%; min-height: 100%; overflow: visible !important;
                }
                #rikkahub-pan-scene { display: flex; flex-direction: column; justify-content: safe center; align-items: center; }
                #renderer.leaflet-container { overflow: hidden !important; touch-action: auto !important; }
            """.trimIndent(),
        )
    document.select("#rikkahub-fullscreen-pan-script,#rikkahub-fullscreen-background-script").remove()
    document.body().appendElement("script").attr("id", "rikkahub-fullscreen-pan-script").attr("src",
        "https://rikkahub.local/assets/html/fullscreen-pan.js")
    document.body().appendElement("script").attr("id", "rikkahub-fullscreen-background-script").append(
        """
        (function() {
        const initial = document.body.classList.contains('rikka-preview-global-background');
        document.documentElement.classList.remove('rikka-preview-global-background');
        document.body.classList.remove('rikka-preview-global-background');
        const container = document.getElementById('diagram-container') || document.getElementById('renderer');
        if (container && !container.classList.contains('leaflet-container')) {
          const paper = getComputedStyle(container).backgroundColor;
          if (paper !== 'rgba(0, 0, 0, 0)' && paper !== 'transparent') document.body.style.setProperty('--rikka-preview-paper', paper);
        }
        window.rikkaSetPreviewBackground = function(enabled) {
          document.documentElement.classList.toggle('rikka-preview-global-background', enabled);
          document.body.classList.toggle('rikka-preview-global-background', enabled);
        };
        window.rikkaSetPreviewBackground(initial);
        })();
        """.trimIndent(),
    )
    document.head().appendElement("style").attr("id", "rikkahub-fullscreen-background-style").append(
        """
        html.rikka-preview-global-background, body.rikka-preview-global-background {
          background: transparent !important; background-color: transparent !important;
          background-image: none !important;
        }
        body.rikka-preview-global-background #renderer:not(.leaflet-container),
        body.rikka-preview-global-background #diagram-container { background: transparent !important; }
        body.rikka-preview-global-background #diagram-container .mermaid,
        body.rikka-preview-global-background #renderer:not(.leaflet-container) > svg,
        body.rikka-preview-global-background #renderer:not(.leaflet-container) > canvas {
          background-color: var(--rikka-preview-paper); border-radius: 8px;
        }
        """.trimIndent(),
    )
    return document.outerHtml()
}

@Composable
fun WebViewPage(url: String, contentId: String, onClose: (() -> Unit)? = null) {
    if (url.isNotEmpty()) {
        WebViewPageContent(url, contentId, onClose)
        return
    }
    val settings = LocalSettings.current
    val appearance = settings.advancedAppearanceSetting
    val active = settings.isFullscreenPreviewBackgroundActive()
    val baseScheme = LocalBackgroundBaseColorScheme.current ?: MaterialTheme.colorScheme
    val readability = me.rerere.rikkahub.ui.theme.rememberBackgroundReadability(
        appearance.globalBackground.takeIf { active }, appearance.globalBackgroundOpacity, false,
        overlayTopAlpha = .28f, overlayBottomAlpha = .42f,
    )
    val spec = if (active) AppearanceBackgroundSpec(appearance.globalBackground,
        appearance.globalBackgroundOpacity, appearance.globalBackgroundBlurRadius,
        foreground = readability.foreground, readability = readability) else null
    // Preview and its menus belong to a separate window, not the assistant's background scope.
    CompositionLocalProvider(LocalAppearanceBackground provides spec,
        LocalGlobalBackgroundActive provides active, LocalPageSurfaceStyle provides appearance.pageSurfaceStyle,
        LocalGlassBackdrop provides null) {
        MaterialTheme(colorScheme = baseScheme) { WebViewPageContent(url, contentId, onClose) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebViewPageContent(url: String, contentId: String, onClose: (() -> Unit)?) {
    val context = LocalContext.current
    val settings = LocalSettings.current
    val settingsStore: SettingsStore = koinInject()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val previewBackgroundEnabled = url.isEmpty() && settings.isFullscreenPreviewBackgroundActive()
    val baseScheme = LocalBackgroundBaseColorScheme.current ?: MaterialTheme.colorScheme
    val cached by produceState<Pair<String, Result<String>>?>(null, contentId, url) {
        if (url.isEmpty()) {
            val result = withContext(Dispatchers.IO) { runCatching {
                val html = WebViewContentCache.load(context.cacheDir, contentId)
                    ?: error("预览缓存已不存在，请返回聊天重新打开。")
                prepareFullscreenPreviewHtml(html, previewBackgroundEnabled)
            } }
            value = contentId to result
        }
    }
    val preview = cached?.takeIf { it.first == contentId }?.second
    val state = if (url.isNotEmpty()) {
        rememberWebViewState(
            url = url,
            settings = {
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
            })
    } else {
        val content = preview?.getOrNull().orEmpty()
        rememberWebViewState(
            data = content,
            baseUrl = WEB_VIEW_BASE_URL,
            mimeType = "text/html",
            settings = {
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
            }
        )
    }

    var showDropdown by remember { mutableStateOf(false) }
    var showConsoleSheet by remember { mutableStateOf(false) }
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    // Update CSS after loading too: AndroidView.update can run before the page defines the function.
    // Do not reload/rebuild the document when toggling; preserve pan, zoom and renderer state.
    LaunchedEffect(state.webView, state.loadGeneration, state.isLoading, previewBackgroundEnabled) {
        if (url.isEmpty() && !state.isLoading) state.webView?.evaluateJavascript(
            "window.rikkaSetPreviewBackground && window.rikkaSetPreviewBackground($previewBackgroundEnabled);", null)
    }

    BackHandler(onClose != null) { onClose?.invoke() }
    BackHandler(url.isNotEmpty() && state.canGoBack) {
        state.goBack()
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (url.isNotEmpty()) {
            TopAppBar(
                title = {
                    Text(
                        text = state.pageTitle?.takeIf { it.isNotEmpty() } ?: state.currentUrl
                        ?: "",
                        maxLines = 1,
                        style = MaterialTheme.typography.titleSmall
                    )
                },
                navigationIcon = {
                    BackButton(onClick = onClose)
                },
                actions = {
                    IconButton(onClick = { state.reload() }) {
                        Icon(
                            HugeIcons.Refresh01,
                            contentDescription = stringResource(R.string.webview_refresh),
                        )
                    }

                    IconButton(
                        onClick = { state.goForward() },
                        enabled = state.canGoForward
                    ) {
                        Icon(
                            HugeIcons.ArrowRight01,
                            contentDescription = stringResource(R.string.webview_forward),
                        )
                    }

                    val urlHandler = LocalUriHandler.current
                    IconButton(
                        onClick = { showDropdown = true }
                    ) {
                        Icon(
                            HugeIcons.MoreVertical,
                            contentDescription = stringResource(R.string.more_options),
                        )

                        DropdownMenu(
                            expanded = showDropdown,
                            onDismissRequest = { showDropdown = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.webview_open_in_browser)) },
                                leadingIcon = { Icon(HugeIcons.Earth, contentDescription = null) },
                                onClick = {
                                    showDropdown = false
                                    state.currentUrl?.let { url ->
                                        if (url.isNotBlank()) {
                                            urlHandler.openUri(url)
                                        }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.webview_console_logs)) },
                                leadingIcon = { Icon(HugeIcons.Bug01, contentDescription = null) },
                                onClick = {
                                    showDropdown = false
                                    showConsoleSheet = true
                                }
                            )
                        }
                    }
                }
            )
            }
        }
    ) {
        Box(Modifier.fillMaxSize().then(if (url.isEmpty()) Modifier.background(baseScheme.background.copy(alpha = 1f)) else Modifier)) {
        if (previewBackgroundEnabled) {
            // The preview is its own window; never register it as the chat's backdrop source.
            CompositionLocalProvider(LocalGlassBackdrop provides null) {
                MaterialTheme(colorScheme = baseScheme) { GlobalAppBackground(settings, Modifier.fillMaxSize()) }
            }
        }
        if (url.isEmpty() && preview == null) {
            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(it))
        } else if (url.isEmpty() && preview?.isFailure == true) {
            Text("预览缓存无法读取，请返回聊天重新打开。", Modifier.padding(it).padding(16.dp))
        } else {
            WebView(
                state = state,
                transparentBackground = url.isEmpty(),
                onUpdated = { view ->
                    if (url.isEmpty()) view.evaluateJavascript(
                        "window.rikkaSetPreviewBackground && window.rikkaSetPreviewBackground($previewBackgroundEnabled);",
                        null,
                    )
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(it),
            )
        }
        if (url.isEmpty()) {
            val navigator = LocalNavController.current
            Row(
                Modifier.align(Alignment.BottomEnd)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.union(WindowInsets.navigationBarsIgnoringVisibility)
                            .only(WindowInsetsSides.Bottom + WindowInsetsSides.End)
                    )
                    .padding(12.dp)
                    .background(baseScheme.surface.copy(alpha = .94f), MaterialTheme.shapes.large)
            ) {
                CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides baseScheme.onSurface,
                    androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 40.dp,
                ) {
                    IconButton(onClick = onClose ?: { navigator.popBackStack() }, modifier = Modifier.size(40.dp)) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.back), modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = { state.webView?.evaluateJavascript("window.rikkaResetPan && window.rikkaResetPan();", null) },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(HugeIcons.Refresh01, contentDescription = "重置视图", modifier = Modifier.size(18.dp))
                    }
                    androidx.compose.material3.IconToggleButton(
                        checked = settings.advancedAppearanceSetting.applyGlobalBackgroundToFullscreenPreview,
                        enabled = settings.isGlobalBackgroundActive(),
                        modifier = Modifier.size(40.dp),
                        onCheckedChange = { enabled -> scope.launch {
                            try { settingsStore.updateAdvancedAppearance { it.copy(applyGlobalBackgroundToFullscreenPreview = enabled) } }
                            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) { toaster.show("预览背景设置保存失败，请重试", type = com.dokar.sonner.ToastType.Error) }
                        } },
                    ) {
                        Icon(HugeIcons.View, modifier = Modifier.size(18.dp),
                            contentDescription = stringResource(if (settings.advancedAppearanceSetting.applyGlobalBackgroundToFullscreenPreview)
                                R.string.webview_preview_background_disable else R.string.webview_preview_background_enable))
                    }
                    IconButton(onClick = { showConsoleSheet = true }, modifier = Modifier.size(40.dp)) {
                        Icon(HugeIcons.Bug01, contentDescription = stringResource(R.string.webview_console_logs), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        }
    }

    if (showConsoleSheet) {
        ModalBottomSheet(
            onDismissRequest = { showConsoleSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.webview_console_logs),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                SelectionContainer {
                    LazyColumn {
                        items(state.consoleMessages) { message ->
                            Text(
                                text = "${message.messageLevel().name}: ${message.message()}\n" +
                                    "Source: ${message.sourceId()}:${message.lineNumber()}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = JetbrainsMono,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                color = when (message.messageLevel().name) {
                                    "ERROR" -> MaterialTheme.colorScheme.error
                                    "WARNING" -> MaterialTheme.colorScheme.secondary
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }

                if (state.consoleMessages.isEmpty()) {
                    Text(
                        text = "No console messages",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}
