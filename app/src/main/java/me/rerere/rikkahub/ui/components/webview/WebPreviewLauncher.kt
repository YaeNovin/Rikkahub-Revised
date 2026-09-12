package me.rerere.rikkahub.ui.components.webview

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import com.dokar.sonner.ToastType

internal val LocalBeforeWebPreview = staticCompositionLocalOf<() -> Unit> { {} }

/** Build, hash and store HTML away from the UI thread. Suppress double taps while writing. */
@Composable
internal fun rememberWebPreviewLauncher(): (() -> String) -> Unit {
    val context = LocalContext.current.applicationContext
    val nav = LocalNavController.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val beforePreview by rememberUpdatedState(LocalBeforeWebPreview.current)
    val chatPreview by rememberUpdatedState(LocalChatWebPreview.current)
    var busy by remember { mutableStateOf(false) }
    return { build ->
        if (!busy) {
            busy = true
            scope.launch {
                try {
                    val id = withContext(Dispatchers.IO) { WebViewContentCache.store(context.cacheDir, build()) }
                    val host = chatPreview
                    if (host != null) host(id) else {
                        beforePreview()
                        nav.navigate(Screen.WebView(contentId = id))
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { toaster.show("无法打开图形预览，请检查存储空间后重试。", type = ToastType.Error) }
                finally { busy = false }
            }
        }
    }
}
