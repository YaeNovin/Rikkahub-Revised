package me.rerere.rikkahub

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.richtext.*
import me.rerere.rikkahub.ui.context.LocalSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class LinkActionsGestureTest {
    init {
        androidx.test.espresso.IdlingPolicies.setMasterPolicyTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        androidx.test.espresso.IdlingPolicies.setIdlingResourceTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
    }
    @get:Rule val compose = createComposeRule()
    private val url = "https://example.com/documentation"
    private val text = buildAnnotatedString { withLink(LinkAnnotation.Url(url)) { append("Documentation") } }

    @Test fun longPressInsideSelectionOpensMenuWithoutOpeningBrowser() {
        var opened = 0
        var selected: LinkTarget? = null
        compose.setContent {
            CompositionLocalProvider(
                LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { opened++ } },
                LocalLinkActionHandler provides { selected = it },
            ) { MaterialTheme { SelectionContainer { LinkText(text, Modifier.testTag("link")) } } }
        }
        compose.onNodeWithTag("link").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(LinkTarget(url, "Documentation"), selected); assertEquals(0, opened) }
    }

    @Test fun shortTapStillOpensLinkOnce() {
        var opened = 0
        var menus = 0
        compose.setContent {
            CompositionLocalProvider(
                LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { assertEquals(url, uri); opened++ } },
                LocalLinkActionHandler provides { menus++ },
            ) { MaterialTheme { SelectionContainer { LinkText(text, Modifier.testTag("link")) } } }
        }
        compose.onNodeWithTag("link").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, opened); assertEquals(0, menus) }
    }

    @Test fun holdingAdjacentTextDoesNotOpenTheLinkMenuAndTheLinkStillTaps() {
        var opened = 0
        var menus = 0
        val sentence = buildAnnotatedString {
            append("See ")
            append(text)
            append(" for details.")
        }
        compose.setContent {
            CompositionLocalProvider(
                LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) {
                        assertEquals(url, uri)
                        opened++
                    }
                },
                LocalLinkActionHandler provides { menus++ },
            ) {
                MaterialTheme { SelectionContainer { LinkText(sentence, Modifier.testTag("sentence")) } }
            }
        }
        val node = compose.onNodeWithTag("sentence")
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.first()
        node.performTouchInput { longClick(layout.getBoundingBox(1).center) }
        node.performTouchInput { click(layout.getBoundingBox(6).center) }
        compose.runOnIdle { assertEquals(0, menus); assertEquals(1, opened) }
    }

    @Test fun webPreviewMenuUsesTheNormalizedDestination() {
        var target by mutableStateOf<LinkTarget?>(LinkTarget("www.example.com/a b", "Source"))
        var preview: String? = null
        compose.setContent {
            CompositionLocalProvider(LocalSettings provides Settings()) {
                MaterialTheme {
                    LinkActionsSheet(target, { target = null }, {}, { preview = it })
                }
            }
        }
        compose.onNodeWithText("预览网页").performClick()
        compose.runOnIdle { assertNull(target); assertEquals("https://www.example.com/a%20b", preview) }
    }

    @Test fun scrollStartingOnLinkDoesNotOpenItOrShowMenu() {
        var opened = 0
        var menus = 0
        var scroll = 0
        compose.setContent {
            val state = rememberScrollState()
            SideEffect { scroll = state.value }
            CompositionLocalProvider(
                LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { opened++ } },
                LocalLinkActionHandler provides { menus++ },
            ) { MaterialTheme {
                Column(Modifier.height(240.dp).verticalScroll(state)) {
                    Spacer(Modifier.height(100.dp))
                    LinkText(text, Modifier.testTag("link"))
                    Spacer(Modifier.height(1200.dp))
                }
            } }
        }
        compose.onNodeWithTag("link").performTouchInput { swipe(center, Offset(center.x, -70f), 200) }
        compose.runOnIdle { assertEquals(0, opened); assertEquals(0, menus); assertTrue(scroll > 0) }
    }

    @Test fun menuModelActionProducesDraftAndPreservesTheActualUrl() {
        var target by mutableStateOf<LinkTarget?>(null)
        var draft = ""
        compose.setContent {
            CompositionLocalProvider(LocalSettings provides Settings(), LocalLinkActionHandler provides { target = it }) {
                MaterialTheme {
                    SelectionContainer { LinkText(text, Modifier.testTag("link")) }
                    LinkActionsSheet(target, { target = null }, { draft = it })
                }
            }
        }
        compose.onNodeWithTag("link").performTouchInput { longClick() }
        compose.onNodeWithText("复制链接").assertIsDisplayed()
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = instrumentation.uiAutomation.takeScreenshot()
        if (image != null) {
            val file = File(instrumentation.targetContext.getExternalFilesDir(null), "link-actions-qa.png")
            file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        compose.onNodeWithText("让模型总结网页").performClick()
        compose.runOnIdle { assertTrue(draft.contains(url)); assertTrue(draft.contains("无法读取网页")); assertNull(target) }
    }
}
