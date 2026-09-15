package me.rerere.rikkahub.ui.components.ui

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import me.rerere.rikkahub.ui.context.LocalGlobalBackgroundActive

/** Inline inputs inherit the foreground chosen for their actual enclosing surface. */
@Composable
internal fun appearanceOutlinedTextFieldColors(): TextFieldColors {
    val foreground = LocalContentColor.current
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = foreground,
        unfocusedTextColor = foreground,
        disabledTextColor = foreground.copy(alpha = foreground.alpha * .38f),
        errorTextColor = foreground,
        focusedLabelColor = foreground,
        unfocusedLabelColor = foreground.copy(alpha = foreground.alpha * .8f),
        focusedPlaceholderColor = foreground.copy(alpha = foreground.alpha * .65f),
        unfocusedPlaceholderColor = foreground.copy(alpha = foreground.alpha * .65f),
        focusedLeadingIconColor = foreground,
        unfocusedLeadingIconColor = foreground,
        focusedTrailingIconColor = foreground,
        unfocusedTrailingIconColor = foreground,
        unfocusedBorderColor = foreground.copy(alpha = foreground.alpha * .38f),
        disabledBorderColor = foreground.copy(alpha = foreground.alpha * .12f),
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
        errorContainerColor = Color.Transparent,
    )
}

/** Keep normal theme accents without a backdrop; use its readable foreground over a background. */
@Composable
internal fun appearanceTextButtonColors(): ButtonColors =
    if (LocalGlobalBackgroundActive.current) ButtonDefaults.textButtonColors(
        contentColor = LocalContentColor.current,
        disabledContentColor = LocalContentColor.current.copy(alpha = LocalContentColor.current.alpha * .38f),
    ) else ButtonDefaults.textButtonColors()

@Composable
internal fun AppearanceFormItem(
    modifier: Modifier = Modifier,
    label: @Composable () -> Unit,
    description: (@Composable () -> Unit)? = null,
    tail: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val supportingStyle = MaterialTheme.typography.bodySmall.copy(
        color = LocalContentColor.current.copy(alpha = LocalContentColor.current.alpha * .8f),
    )
    Box(modifier) {
        FormItem(
            label = label, tail = tail, content = content,
            description = if (description == null) null else {
                { ProvideTextStyle(supportingStyle) { description() } }
            },
        )
    }
}
