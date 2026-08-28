package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.resolveChatBackground
import me.rerere.rikkahub.ui.components.ui.BlurredBackgroundImage

@Composable
fun AssistantBackground(setting: Settings, modifier: Modifier) {
    val background = setting.resolveChatBackground()
    if (background.useGradientBackground) {
        MeshGradientBackground(modifier = modifier)
        return
    }
    if (!background.background.isNullOrBlank()) {
        BlurredBackgroundImage(
            background = background.background,
            opacity = background.opacity,
            blurRadius = background.blurRadius,
            overlayTopAlpha = 0.32f,
            overlayBottomAlpha = 0.52f,
            modifier = modifier,
        )
    }
}
