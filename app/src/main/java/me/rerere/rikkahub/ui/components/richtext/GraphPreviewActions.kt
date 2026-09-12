package me.rerere.rikkahub.ui.components.richtext

import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.View
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.webview.rememberWebPreviewLauncher
import me.rerere.rikkahub.ui.context.LocalToaster

/** Match Mermaid's preview/image actions. Header download continues to save the original source. */
@Composable
internal fun GraphPreviewActions(html: String, view: WebView?) {
    val preview = rememberWebPreviewLauncher()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    val saveImage = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri == null) { saving = false } else {
            val renderer = view
            scope.launch {
                var bitmap: Bitmap? = null
                try {
                    require(renderer != null && renderer.isAttachedToWindow && renderer.width > 0 && renderer.height > 0)
                    val scale = minOf(1f, kotlin.math.sqrt(4_000_000f / (renderer.width.toLong() * renderer.height)))
                    val image = Bitmap.createBitmap((renderer.width * scale).toInt().coerceAtLeast(1), (renderer.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bitmap = image
                    val canvas = Canvas(image)
                    canvas.scale(scale, scale)
                    renderer.draw(canvas)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        requireNotNull(context.contentResolver.openOutputStream(uri)).use { output ->
                            check(image.compress(Bitmap.CompressFormat.PNG, 100, output))
                        }
                    }
                    toaster.show(context.getString(R.string.mermaid_export_success), type = ToastType.Success)
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    toaster.show(context.getString(R.string.mermaid_export_failed), type = ToastType.Error)
                } finally { bitmap?.recycle(); saving = false }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.End) {
        IconButton(onClick = { preview { html } }) {
            Icon(HugeIcons.View, stringResource(R.string.code_block_preview))
        }
        IconButton(enabled = view != null && !saving, onClick = {
            val renderer = view ?: return@IconButton
            saving = true
            renderer.evaluateJavascript("document.readyState === 'complete' && window.__rikkaRenderStatus !== 'loading' && window.__rikkaRenderStatus !== 'error'") { ready ->
                if (ready != "true" || !renderer.isAttachedToWindow) {
                    saving = false
                    toaster.show("图形尚未完成渲染，请稍后重试。", type = ToastType.Error)
                } else saveImage.launch("graph_${System.currentTimeMillis()}.png")
            }
        }) {
            Icon(HugeIcons.Download01, "保存当前图形预览")
        }
    }
}
