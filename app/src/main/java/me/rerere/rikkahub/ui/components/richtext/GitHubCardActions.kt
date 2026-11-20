package me.rerere.rikkahub.ui.components.richtext

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.ui.components.ui.AppearanceModalBottomSheet
import me.rerere.rikkahub.utils.exportImage
import me.rerere.rikkahub.utils.getActivity

/** Captures the already visible native card. It never fetches repository or image data. */
@Composable
internal fun GitHubCardActions(show: Boolean, onDismiss: () -> Unit, url: String, layer: GraphicsLayer,
    renderImage: (suspend () -> Bitmap)? = null,
    content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val background = MaterialTheme.colorScheme.surface.toArgb()
    var preview by remember { mutableStateOf(false) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewFailed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun imageAction(share: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            var bitmap: Bitmap? = null
            var phase = "capture"
            try {
                if (renderImage != null) bitmap = renderImage()
                else {
                    val snapshot = layer.toImageBitmap().asAndroidBitmap()
                    check(snapshot.width > 0 && snapshot.height > 0 && snapshot.width.toLong() * snapshot.height <= 8_000_000)
                    phase = "rasterize"
                    bitmap = requireNotNull(snapshot.copy(Bitmap.Config.ARGB_8888, true))
                    Canvas(bitmap).drawColor(background, PorterDuff.Mode.DST_OVER)
                }
                phase = if (share) "share" else "gallery"
                if (share) shareGitHubCard(context, bitmap, url)
                else context.exportImage(requireNotNull(context.getActivity()), bitmap, "GitHub_${System.currentTimeMillis()}.png")
                if (!share) message = "卡片已保存至相册"
                onDismiss()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                runCatching { me.rerere.common.android.Logging.logError(
                    name = "GitHub 卡片导出失败", summary = "$phase: ${error.javaClass.simpleName}",
                    // Stack frames only: exception messages may contain private names or paths.
                    details = error.stackTrace.joinToString("\n") { "at $it" }, tag = "GITHUB_CARD_EXPORT") }
                message = "无法导出卡片，请稍后重试或检查相册权限"
            }
            finally { bitmap?.recycle(); busy = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) imageAction(false) else message = "保存至相册需要存储权限"
    }
    if (show) AppearanceModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("GitHub 仓库卡片", style = MaterialTheme.typography.titleLarge)
            TextButton(enabled = !busy, onClick = { imageAction(true) }) { Text("分享") }
            TextButton(enabled = !busy, onClick = { onDismiss(); preview = true }) { Text("预览卡片") }
            TextButton(enabled = !busy, onClick = {
                if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                    permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                else imageAction(false)
            }) { Text("保存至相册") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    LaunchedEffect(preview) {
        previewBitmap = null; previewFailed = false
        if (preview && renderImage != null) {
            try { previewBitmap = renderImage() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { previewFailed = true }
        }
    }
    if (preview) AppearanceModalBottomSheet(onDismissRequest = { preview = false }) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("卡片预览", style = MaterialTheme.typography.titleLarge)
            if (renderImage == null) content()
            else if (previewFailed) Text("暂时无法生成卡片预览")
            else previewBitmap?.let { Image(it.asImageBitmap(), "GitHub 社交预览卡片", Modifier.fillMaxWidth().aspectRatio(2f)) }
                ?: LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = { preview = false }) { Text("关闭") }
        }
    }
    message?.let { text ->
        LaunchedEffect(text) { android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show(); message = null }
    }
}

private suspend fun shareGitHubCard(context: Context, bitmap: Bitmap, url: String) {
    val file = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "github-card-shares").apply { mkdirs() }
        directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
        File.createTempFile("card-", ".png", directory).also { file ->
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, url)
        clipData = ClipData.newUri(context.contentResolver, "GitHub card", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享 GitHub 卡片"))
}
