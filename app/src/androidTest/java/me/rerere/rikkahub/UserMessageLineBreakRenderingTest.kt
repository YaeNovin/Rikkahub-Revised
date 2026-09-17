package me.rerere.rikkahub

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.context.LocalSettings
import org.junit.Rule
import org.junit.Test

class UserMessageLineBreakRenderingTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun userMessageKeepsSoftLineBreaksWhenHtmlForcesRichTextRenderer() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSettings provides Settings()) {
                    MarkdownBlock(
                        content = "first line\nsecond line\n\n<div>fragment</div>",
                        preserveSoftLineBreaks = true,
                    )
                }
            }
        }

        compose.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                compose.onNodeWithText("first line\nsecond line", useUnmergedTree = true)
                    .fetchSemanticsNode()
            }.isSuccess
        }
        compose.onNodeWithText("first line\nsecond line", useUnmergedTree = true).assertIsDisplayed()
    }
}
