package me.rerere.rikkahub.ui.components.webview

import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import me.rerere.rikkahub.ui.pages.webview.WebViewPage

internal val LocalChatWebPreview = staticCompositionLocalOf<((String) -> Unit)?> { null }
internal val LocalChatWebPreviewVisible = compositionLocalOf { false }

/** Owned by ChatPage rather than a lazy message: opening a preview does not pop/recreate the list. */
@Composable
internal fun ChatWebPreviewHost(contentId: String?, onDismiss: () -> Unit) {
    if (contentId == null) return
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
        dismissOnClickOutside = false,
    )) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            val controller = window?.let { WindowCompat.getInsetsController(it, view) }
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
        }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            WebViewPage(url = "", contentId = contentId, onClose = onDismiss)
        }
    }
}
