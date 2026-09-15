package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Keep status text legible on the same adaptive surface as the form, including errors. */
@Composable
internal fun SpeechInfoText(text: String, error: Boolean = false, modifier: Modifier = Modifier) {
    val foreground = LocalContentColor.current
    Text(
        text = if (error) "错误：$text" else text,
        modifier = modifier,
        color = foreground.copy(alpha = foreground.alpha * if (error) 1f else .8f),
        style = MaterialTheme.typography.bodySmall,
    )
}
