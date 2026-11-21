package me.rerere.rikkahub.ui.components.richtext

import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.caverock.androidsvg.SVG
import com.dokar.sonner.ToastType
import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine
import me.rerere.rikkahub.ui.context.LocalToaster
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import kotlin.coroutines.resume

/** Export engine SVGs page by page as vector PDF, independent of WebView scroll/viewport. */
internal suspend fun exportScorePdf(view: WebView, target: File): Int = withTimeout(120_000) {
    val info = JSONObject(view.scoreJavaScript("JSON.stringify(window.rikkaScoreInfo())"))
    val count = info.getInt("pages").also { require(it in 1..64) }
    val revision = info.getInt("revision")
    var totalChars = 0
    withContext(Dispatchers.IO) { target.parentFile?.mkdirs() }
    try {
        val document = PdfDocument()
        try {
            for (index in 1..count) {
                ensureActive()
                val source = view.scoreJavaScript("window.rikkaScoreExportPage($index,$revision)")
                require(source.length in 1..2_000_000) { "单页谱面过大，无法导出" }
                totalChars += source.length
                require(totalChars <= 16_000_000) { "整谱过大，请分段导出" }
                withContext(Dispatchers.IO) {
                    val svg = SVG.getFromString(source)
                    val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, index).create())
                    page.canvas.drawColor(Color.WHITE)
                    svg.renderToCanvas(page.canvas, RectF(18f, 18f, 577f, 824f))
                    document.finishPage(page)
                }
            }
            // Catch a late reflow rather than silently mixing page layouts in one PDF.
            val after = JSONObject(view.scoreJavaScript("JSON.stringify(window.rikkaScoreInfo())"))
            require(after.getInt("revision") == revision && after.getInt("pages") == count) { "谱面发生变化，请重新导出" }
            withContext(Dispatchers.IO) { target.outputStream().use(document::writeTo) }
        } finally { withContext(NonCancellable + Dispatchers.IO) { document.close() } }
        count
    } catch (error: Throwable) {
        withContext(NonCancellable + Dispatchers.IO) { target.delete() }
        throw error
    }
}

private suspend fun WebView.scoreJavaScript(script: String): String = withContext(Dispatchers.Main.immediate) {
    check(isAttachedToWindow) { "谱面已关闭" }
    withTimeout(15_000) {
        suspendCancellableCoroutine { continuation ->
            evaluateJavascript("(function(){try{return JSON.stringify({value:($script)})}catch(e){return JSON.stringify({error:String(e.message||e)})}})()") { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        }.let { result ->
            val envelope = JSONObject(JSONTokener(result).nextValue() as? String ?: error("谱面尚未就绪"))
            check(!envelope.has("error")) { envelope.optString("error") }
            envelope.getString("value")
        }
    }
}

@Composable
internal fun ScorePdfExportAction(view: WebView?) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri == null) saving = false else scope.launch {
            val file = File(context.cacheDir, "score-export-${java.util.UUID.randomUUID()}.pdf")
            try {
                val count = exportScorePdf(requireNotNull(view), file)
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri)).use { output -> file.inputStream().use { it.copyTo(output) } }
                }
                toaster.show("已导出整谱，共 $count 页", type = ToastType.Success)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { toaster.show("乐谱导出失败：${error.message.orEmpty().take(120)}", type = ToastType.Error) }
            finally { withContext(NonCancellable + Dispatchers.IO) { file.delete() }; saving = false }
        }
    }
    TextButton(enabled = view != null && !saving, onClick = {
        saving = true
        launcher.launch("score_${System.currentTimeMillis()}.pdf")
    }) { Text(if (saving) "正在导出…" else "整谱 PDF") }
}
