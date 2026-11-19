package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.datastore.AdvancedAppearanceSetting
import me.rerere.rikkahub.ui.components.ui.CardGroup

@Composable
internal fun MicaAppearanceSettingsCard(
    appearance: AdvancedAppearanceSetting,
    onApply: () -> Unit,
    update: ((AdvancedAppearanceSetting) -> AdvancedAppearanceSetting) -> Unit,
) {
    CardGroup(Modifier.padding(horizontal = 8.dp), title = { Text("云母与亚克力") }) {
        item(onClick = onApply, headlineContent = { Text("应用云母 + 亚克力外观") }, supportingContent = {
            Text("页面与侧栏使用云母，输入区与浮层使用亚克力。无背景也可使用；保留图片、透明度、模糊强度及效果开关。")
        })
        item(headlineContent = { Text("云母染色强度") }, supportingContent = {
            Column {
                AdvancedAppearanceSlider(description = "调整正式云母材质的低彩度染色。有背景时参考背景颜色，无背景时参考主题色。",
                    value = me.rerere.rikkahub.ui.components.ui.finiteAppearanceValue(appearance.micaTintStrength, 0f, 1f, .45f),
                    valueRange = 0f..1f, steps = 19, enabled = true,
                    valueLabel = { "${(it * 100).toInt()}%" },
                    onValueChange = { strength -> update { it.copy(micaTintStrength = strength) } })
                Text("云母没有实时模糊；磨砂、液态玻璃和亚克力才启用相应模糊设置。", style = MaterialTheme.typography.bodySmall)
            }
        })
    }
}
