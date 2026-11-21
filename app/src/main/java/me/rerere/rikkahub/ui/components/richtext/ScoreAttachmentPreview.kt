package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import com.dokar.sonner.ToastType
import kotlinx.coroutines.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.render.ScoreDocuments
import me.rerere.rikkahub.ui.components.webview.rememberWebPreviewLauncher
import me.rerere.rikkahub.ui.context.LocalToaster
import java.io.File
import java.net.URI

@Composable
internal fun rememberScoreAttachmentPreview(): (UIMessagePart.Document, () -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    val preview = rememberWebPreviewLauncher()
    val toaster = LocalToaster.current
    val colors by rememberUpdatedState(me.rerere.rikkahub.ui.theme.LocalBackgroundBaseColorScheme.current ?: MaterialTheme.colorScheme)
    val meiEnabled by rememberUpdatedState(me.rerere.rikkahub.ui.context.LocalSettings.current.displaySetting.meiRenderingEnabled)
    var busy by remember { mutableStateOf(false) }
    return { part, fallback ->
        if (!ScoreDocuments.isCandidate(part.fileName, part.mime)) fallback()
        else if (!busy) {
            busy = true
            scope.launch {
                try {
                    val score = withContext(Dispatchers.IO) {
                        val file = if (part.url.startsWith("file:", true)) File(URI(part.url)) else File(part.url)
                        ScoreDocuments.read(file, part.fileName, part.mime)
                    }
                    if (score?.renderer == "mei" && !meiEnabled) {
                        toaster.show("MEI 渲染已关闭，可在常规设置中开启", type = ToastType.Info)
                    } else if (score == null) fallback() else {
                        val scheme = colors
                        preview { buildInteractiveRendererHtml(requireNotNull(InteractiveCodeRenderer.fromLanguage(score.renderer)), score.source, scheme) }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { toaster.show("无法预览乐谱：${error.message.orEmpty().take(160)}", type = ToastType.Error) }
                finally { busy = false }
            }
        }
    }
}
