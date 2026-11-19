package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ui.*

/** Buttons float over the list; the empty space is neither painted nor clickable. */
@Composable
internal fun ChatFloatingHeader(
    settings: Settings,
    hazeState: HazeState,
    showNavigation: Boolean,
    onNavigation: () -> Unit,
    onNewChat: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    menu: @Composable () -> Unit = {},
    notices: @Composable ColumnScope.() -> Unit = {},
) {
    val appearance = settings.advancedAppearanceSetting
    val display = settings.displaySetting
    val capabilities = LocalAdvancedAppearanceCapabilities.current
    val tint = MaterialTheme.colorScheme.surfaceContainer
    Box(modifier.testTag("chat_floating_header")) {
        if (display.showTopBarBackground) {
            val blur = shouldApplyTopBarBlur(true, display.enableTopBarBlur,
                appearance.enableTopBarPerformanceEffects, capabilities.supportsRealtimeBlur,
                true, appearance.pageSurfaceStyle)
            Box(Modifier.matchParentSize()
                .then(if (blur) Modifier.hazeBlur(HazeInput.Sources(hazeState),
                    style = backgroundOnlyBlurStyle(capabilities.limitLiveBlur(display.topBarBlurRadius))) else Modifier)
                .background(Brush.verticalGradient(listOf(
                    tint.copy(alpha = finiteAppearanceValue(display.topBarSurfaceOpacity, .35f, 1f, .65f)), Color.Transparent))))
        }
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (showNavigation) HeaderSurface(settings) {
                    IconButton(onNavigation, Modifier.size(48.dp).testTag("chat_open_drawer")) {
                        Icon(HugeIcons.Menu03, "打开侧边栏")
                    }
                } else Spacer(Modifier.size(48.dp))
                HeaderSurface(settings) {
                    Row {
                        IconButton(onNewChat, Modifier.size(48.dp).testTag("chat_new_conversation")) {
                            Icon(HugeIcons.MessageAdd01, "新建聊天")
                        }
                        Box {
                            IconButton(onMenu, Modifier.size(48.dp).testTag("chat_open_menu")) {
                                Icon(HugeIcons.LeftToRightListBullet, "聊天菜单")
                            }
                            menu()
                        }
                    }
                }
            }
            notices()
        }
    }
}

@Composable
private fun HeaderSurface(settings: Settings, content: @Composable BoxScope.() -> Unit) {
    // Button-sized backing is independent of the optional full-width scrim.
    IsolatedAppearanceSurface(style = settings.advancedAppearanceSetting.pageSurfaceStyle,
        surfaceOpacity = settings.displaySetting.topBarSurfaceOpacity,
        blurRadius = 0f, shape = CircleShape, effectsEnabled = false, content = content)
}
