package me.rerere.rikkahub

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.pages.chat.AssistantBackground
import me.rerere.rikkahub.ui.pages.chat.openWhenReady
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class ChatAppearanceRegressionTest {
    @get:Rule(order = 0)
    val timeout = Timeout.seconds(30)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun chatScenePaintsThemeBackgroundWhenNoWallpaperIsConfigured() {
        val expected = Color(0xFF16324A)
        val assistant = Assistant(background = null, useGradientBackground = false)
        val settings = Settings(assistants = listOf(assistant), assistantId = assistant.id)
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(background = expected)) {
                Box(Modifier.background(Color.Magenta)) {
                    AssistantBackground(
                        setting = settings,
                        modifier = Modifier.size(64.dp).testTag("chat_scene"),
                    )
                }
            }
        }

        val pixels = compose.onNodeWithTag("chat_scene").captureToImage().toPixelMap()
        assertEquals(expected.toArgb(), pixels[pixels.width / 2, pixels.height / 2].toArgb())
    }

    @Test
    fun drawerOpenRequestedBeforeFirstLayoutWaitsForAnchors() {
        lateinit var drawerState: DrawerState
        val requestedBeforeLayout = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                drawerState = rememberDrawerState(DrawerValue.Closed)
                LaunchedEffect(Unit) {
                    requestedBeforeLayout.value = drawerState.currentOffset.isNaN()
                    drawerState.openWhenReady()
                }
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet {
                            Box(Modifier.width(200.dp).fillMaxHeight())
                        }
                    },
                ) {
                    Box(Modifier.size(width = 320.dp, height = 640.dp))
                }
            }
        }

        compose.waitUntil(5_000) { requestedBeforeLayout.value && drawerState.isOpen }
        assertTrue(requestedBeforeLayout.value)
        assertTrue(drawerState.isOpen)
    }
}
