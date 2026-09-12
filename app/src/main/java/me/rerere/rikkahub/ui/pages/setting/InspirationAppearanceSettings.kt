package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.InspirationAppearance
import me.rerere.rikkahub.ui.components.ui.CardGroup
import kotlin.math.roundToInt

@Composable
internal fun InspirationAppearanceSettings(value: InspirationAppearance, onChange: (InspirationAppearance) -> Unit) {
    val safe = value.normalized()
    CardGroup(Modifier.padding(horizontal = 8.dp), title = { Text(stringResource(R.string.inspiration_appearance)) }) {
        item(headlineContent = { Text(stringResource(R.string.inspiration_follow_rich)) },
            supportingContent = { Text(stringResource(R.string.inspiration_surface_hint)) }, trailingContent = {
                Switch(safe.followRichContent, { onChange(safe.copy(followRichContent = it)) })
            })
        if (!safe.followRichContent) {
            item(headlineContent = { Text(stringResource(R.string.inspiration_opacity)) }, supportingContent = {
                AdvancedAppearanceSlider(description = stringResource(R.string.inspiration_opacity_hint),
                    value = safe.surfaceOpacity, valueRange = .2f..1f, steps = 15,
                    valueLabel = { "${(it * 100).roundToInt()}%" }, onValueChange = { onChange(safe.copy(surfaceOpacity = it)) })
            })
            item(headlineContent = { Text(stringResource(R.string.inspiration_border)) }, supportingContent = {
                AdvancedAppearanceSlider(description = stringResource(R.string.inspiration_border_hint),
                    value = safe.borderOpacity, valueRange = 0f..1f, steps = 19,
                    valueLabel = { "${(it * 100).roundToInt()}%" }, onValueChange = { onChange(safe.copy(borderOpacity = it)) })
            })
            item(headlineContent = { Text(stringResource(R.string.inspiration_radius)) }, supportingContent = {
                AdvancedAppearanceSlider(description = "", value = safe.cornerRadius, valueRange = 0f..28f, steps = 13,
                    valueLabel = { "${it.roundToInt()} dp" }, onValueChange = { onChange(safe.copy(cornerRadius = it)) })
            })
        }
    }
}
