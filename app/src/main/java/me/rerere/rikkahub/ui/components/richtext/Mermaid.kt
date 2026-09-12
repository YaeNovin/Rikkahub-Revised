package me.rerere.rikkahub.ui.components.richtext

import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.JavascriptInterface
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.View
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.webview.WEB_VIEW_ASSET_URL
import me.rerere.rikkahub.ui.components.webview.WEB_VIEW_BASE_URL
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.components.ui.LocalExportContext
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.escapeHtml
import me.rerere.rikkahub.utils.exportImage
import me.rerere.rikkahub.utils.toCssHex

@Composable
fun Mermaid(
    code: String,
    modifier: Modifier = Modifier,
    showFullScreenAction: Boolean = true,
) {
    val colorScheme = me.rerere.rikkahub.ui.theme.LocalBackgroundBaseColorScheme.current ?: MaterialTheme.colorScheme
    val darkMode = LocalDarkMode.current
    val context = LocalContext.current
    val activity = LocalActivity.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val openPreview = me.rerere.rikkahub.ui.components.webview.rememberWebPreviewLauncher()
    val exportSuccessMessage = stringResource(R.string.mermaid_export_success)
    val exportFailedMessage = stringResource(R.string.mermaid_export_failed)

    val jsInterface = remember(
        activity,
        context,
        exportFailedMessage,
        exportSuccessMessage,
        scope,
        toaster,
    ) {
        MermaidInterface(
            onExportImage = { base64Image ->
                scope.launch {
                    runCatching {
                        val currentActivity = requireNotNull(activity)
                        require(base64Image.isNotBlank() && base64Image.length <= 24 * 1024 * 1024)
                        val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            val imageBytes = Base64.decode(base64Image, Base64.DEFAULT)
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
                            require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 4_000_000L)
                            requireNotNull(BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size))
                        }
                        try {
                            context.exportImage(
                                currentActivity,
                                bitmap,
                                "mermaid_${System.currentTimeMillis()}.png"
                            )
                        } finally {
                            bitmap.recycle()
                        }
                    }.onSuccess {
                        toaster.show(exportSuccessMessage, type = ToastType.Success)
                    }.onFailure { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        error.printStackTrace()
                        toaster.show(exportFailedMessage, type = ToastType.Error)
                    }
                }
            }
        )
    }

    val normalizedCode = remember(code) { normalizeMermaidCode(code) }
    val renderErrorMessage = stringResource(R.string.error_message_rich_content_render)
    val html = remember(normalizedCode, colorScheme, darkMode, renderErrorMessage) {
        buildMermaidHtml(
            code = normalizedCode,
            colorScheme = colorScheme,
            renderErrorMessage = renderErrorMessage,
        )
    }

    val webViewState = rememberWebViewState(
        data = html,
        baseUrl = WEB_VIEW_BASE_URL,
        mimeType = "text/html",
        encoding = "UTF-8",
        interfaces = mapOf(
            "AndroidInterface" to jsInterface
        ),
        settings = {
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
    )
    val previewHeight = 200.dp

    Column(
        modifier = modifier
    ) {
        WebView(
            state = webViewState,
            deferUntilVisible = !LocalExportContext.current,
            preferParentVerticalScroll = true,
            transparentBackground = true,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .height(previewHeight),
        )

        if (activity != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (showFullScreenAction) {
                    IconButton(
                        onClick = {
                            openPreview { html }
                        },
                    ) {
                        Icon(
                            HugeIcons.View,
                            contentDescription = stringResource(R.string.code_block_preview),
                        )
                    }
                }
                IconButton(
                    onClick = {
                        webViewState.webView?.evaluateJavascript(
                            "exportSvgToPng();",
                            null
                        )
                    },
                ) {
                    Icon(
                        HugeIcons.Download01,
                        contentDescription = stringResource(R.string.mermaid_export)
                    )
                }
            }
        }
    }
}

private class MermaidInterface(
    private val onExportImage: (String) -> Unit
) {
    @JavascriptInterface
    fun exportImage(base64Image: String) {
        onExportImage(base64Image)
    }
}

internal fun normalizeMermaidCode(code: String): String {
    val firstContentLine = code.lineSequence().firstOrNull { it.isNotBlank() } ?: return code
    return if (firstContentLine.trim().equals("requirement", ignoreCase = true)) {
        val indentation = firstContentLine.takeWhile(Char::isWhitespace)
        code.replaceFirst(firstContentLine, "${indentation}requirementDiagram")
    } else {
        code
    }
}

internal fun buildMermaidHtml(
    code: String,
    colorScheme: ColorScheme,
    renderErrorMessage: String = "Unable to render this content.",
): String {
    fun foreground(color: androidx.compose.ui.graphics.Color) = me.rerere.rikkahub.ui.theme.readableForegroundColor(color.copy(alpha = 1f)).toCssHex()
    val primaryColor = colorScheme.primaryContainer.toCssHex()
    val secondaryColor = colorScheme.secondaryContainer.toCssHex()
    val tertiaryColor = colorScheme.tertiaryContainer.toCssHex()
    val background = colorScheme.background.toCssHex()
    val surface = colorScheme.surface.toCssHex()
    val onPrimary = foreground(colorScheme.primaryContainer)
    val onSecondary = foreground(colorScheme.secondaryContainer)
    val onTertiary = foreground(colorScheme.tertiaryContainer)
    val onBackground = foreground(colorScheme.surface)
    val errorColor = colorScheme.error.toCssHex()
    val onErrorColor = colorScheme.onError.toCssHex()

    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, minimum-scale=0.25, maximum-scale=8.0, user-scalable=yes">
            <script src="${WEB_VIEW_ASSET_URL}/html/mermaid.min.js"></script>
            <style>
                html, body {
                    width: 100%;
                    min-height: 100%;
                    margin: 0;
                    padding: 0;
                    overflow: auto;
                    background: transparent;
                }
                #diagram-container { width: 100%; overflow: auto; touch-action: pan-x pan-y pinch-zoom; display: flex; align-items: center; justify-content: center; background: $surface; color: $onBackground; border-radius: 8px; }
                .mermaid {
                    visibility: hidden;
                    box-sizing: border-box;
                    margin: 0;
                    padding: 8px;
                    width: 100%;
                    min-width: 0;
                    display: flex;
                    justify-content: center;
                }
                .mermaid svg {
                    background: transparent !important;
                    display: block;
                    width: 100%;
                    max-width: 100%;
                    height: auto;
                    overflow: visible;
                }
            </style>
        </head>
        <body>
             <div id="diagram-container">
                <pre class="mermaid">${code.escapeHtml()}</pre>
            </div>
            <span id="localized-render-error" hidden>${renderErrorMessage.escapeHtml()}</span>
            <script>
              const mermaidNode = document.querySelector('.mermaid');
              window.__rikkaRenderStatus = 'loading';
              const localizedRenderError = document.getElementById('localized-render-error').textContent;
              function showRenderError(error) {
                  window.__rikkaRenderStatus = 'error';
                  console.error(error);
                  mermaidNode.textContent = localizedRenderError;
                  mermaidNode.classList.remove('mermaid');
                  mermaidNode.style.visibility = 'visible';
                  mermaidNode.style.whiteSpace = 'pre-wrap';
              }
              try {
              mermaid.initialize({
                    startOnLoad: false,
                    securityLevel: 'strict',
                    flowchart: { htmlLabels: false, useMaxWidth: true },
                    theme: 'base',
                    themeVariables: {
                        primaryColor: '${primaryColor}',
                        primaryTextColor: '${onPrimary}',
                        primaryBorderColor: '${primaryColor}',

                        secondaryColor: '${secondaryColor}',
                        secondaryTextColor: '${onSecondary}',
                        secondaryBorderColor: '${secondaryColor}',

                        tertiaryColor: '${tertiaryColor}',
                        tertiaryTextColor: '${onTertiary}',
                        tertiaryBorderColor: '${tertiaryColor}',

                        background: '${background}',
                        mainBkg: '${primaryColor}',
                        secondBkg: '${secondaryColor}',

                        lineColor: '${onBackground}',
                        textColor: '${onBackground}',

                        nodeBkg: '${surface}',
                        nodeBorder: '${primaryColor}',
                        clusterBkg: '${surface}',
                        clusterBorder: '${primaryColor}',

                        actorBorder: '${primaryColor}',
                        actorBkg: '${surface}',
                        actorTextColor: '${onBackground}',
                        actorLineColor: '${primaryColor}',

                        taskBorderColor: '${primaryColor}',
                        taskBkgColor: '${primaryColor}',
                        taskTextLightColor: '${onPrimary}',
                        taskTextDarkColor: '${onBackground}',

                        labelColor: '${onBackground}',
                        cScale0: '$primaryColor', cScaleLabel0: '$onPrimary',
                        cScale1: '$secondaryColor', cScaleLabel1: '$onSecondary',
                        cScale2: '$tertiaryColor', cScaleLabel2: '$onTertiary',
                        cScale3: '$primaryColor', cScaleLabel3: '$onPrimary',
                        cScale4: '$secondaryColor', cScaleLabel4: '$onSecondary',
                        cScale5: '$tertiaryColor', cScaleLabel5: '$onTertiary',
                        cScale6: '$primaryColor', cScaleLabel6: '$onPrimary',
                        cScale7: '$secondaryColor', cScaleLabel7: '$onSecondary',
                        cScale8: '$tertiaryColor', cScaleLabel8: '$onTertiary',
                        cScale9: '$primaryColor', cScaleLabel9: '$onPrimary',
                        cScale10: '$secondaryColor', cScaleLabel10: '$onSecondary',
                        cScale11: '$tertiaryColor', cScaleLabel11: '$onTertiary',
                        errorBkgColor: '${errorColor}',
                        errorTextColor: '${onErrorColor}'
                    }
              });
              function fitRenderedDiagram() {
                  const svgElement = document.querySelector('.mermaid svg');
                  if (!svgElement) { showRenderError(new Error('No diagram generated')); return; }
                  mermaidNode.style.visibility = 'visible';
                  svgElement.setAttribute('preserveAspectRatio', 'xMidYMid meet');
                  svgElement.removeAttribute('width');
                  svgElement.removeAttribute('height');
                  svgElement.style.width = '100%';
                  svgElement.style.height = 'auto';
                  window.__rikkaRenderStatus = 'ready';
              }
              const renderTimeout = setTimeout(function() { showRenderError(new Error('Diagram rendering timed out')); }, 20000);
              mermaid.run({ nodes: [mermaidNode] })
                  .then(fitRenderedDiagram)
                  .catch(showRenderError)
                  .finally(function() { clearTimeout(renderTimeout); });
              } catch (error) { showRenderError(error); }

              window.exportSvgToPng = function() {
                try {
                    const svgElement = document.querySelector('.mermaid svg');
                    if (!svgElement) {
                        AndroidInterface.exportImage('');
                        return;
                    }

                    const canvas = document.createElement('canvas');
                    const ctx = canvas.getContext('2d');

                    const svgRect = svgElement.getBoundingClientRect();
                    const width = svgRect.width;
                    const height = svgRect.height;

                    if (!(width > 0 && height > 0)) throw new Error('Empty diagram');
                    const scaleFactor = Math.min(window.devicePixelRatio * 2, 4096 / width, 4096 / height, Math.sqrt(4000000 / (width * height)));
                    canvas.width = Math.max(1, Math.floor(width * scaleFactor));
                    canvas.height = Math.max(1, Math.floor(height * scaleFactor));

                    const svgXml = new XMLSerializer().serializeToString(svgElement);
                    const svgBase64 = btoa(unescape(encodeURIComponent(svgXml)));

                    const img = new Image();
                    img.onload = function() {
                        try {
                        ctx.fillStyle = '${background}';
                        ctx.fillRect(0, 0, canvas.width, canvas.height);
                        ctx.drawImage(img, 0, 0, canvas.width, canvas.height);

                        ctx.font = '14px Arial';
                        ctx.fillStyle = '${onBackground}';
                        ctx.fillText('rikka-ai.com', 20, canvas.height - 10);

                        const pngBase64 = canvas.toDataURL('image/png').split(',')[1];
                        AndroidInterface.exportImage(pngBase64);
                        } catch (error) { AndroidInterface.exportImage(''); }
                    };
                    img.onerror = function(e) {
                        AndroidInterface.exportImage('');
                    }
                    img.src = 'data:image/svg+xml;base64,' + svgBase64;
                } catch (e) {
                    AndroidInterface.exportImage('');
                }
              };
            </script>
        </body>
        </html>
    """.trimIndent()
}
