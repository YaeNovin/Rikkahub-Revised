package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.rerere.rikkahub.ui.components.ui.AnimatedGradientBackground
import me.rerere.rikkahub.ui.components.ui.GradientBackgroundSpec

/** Chat-facing wrapper around the shared gradient renderer. */
@Composable
fun MeshGradientBackground(
    spec: GradientBackgroundSpec,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    AnimatedGradientBackground(
        spec = spec,
        modifier = modifier,
        content = content,
    )
}
