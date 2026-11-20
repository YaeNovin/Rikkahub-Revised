package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import me.rerere.rikkahub.ui.theme.chatQuoteSeed
import me.rerere.rikkahub.ui.theme.currentTextPaletteSeed

internal fun AnnotatedString.linkTargetAt(offset: Int): LinkTarget? {
    if (offset !in text.indices) return null
    val link = getLinkAnnotations(offset, offset + 1).firstOrNull { it.item is LinkAnnotation.Url }
    if (link != null) return LinkTarget((link.item as LinkAnnotation.Url).url, text.substring(link.start, link.end))
    val legacy = getStringAnnotations("URL", offset, offset + 1).firstOrNull() ?: return null
    return LinkTarget(legacy.item, text.substring(legacy.start, legacy.end))
}

private fun TextLayoutResult.linkAt(position: Offset): LinkTarget? {
    val offset = getOffsetForPosition(position)
    val text = layoutInput.text
    fun targetAt(index: Int) = text.linkTargetAt(index)?.takeIf { getBoundingBox(index).contains(position) }
    return targetAt(offset) ?: targetAt(offset - 1)
}

/** Handle touches within a link's glyphs and leave surrounding prose to native text selection. */
@Composable
fun LinkText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    style: TextStyle = LocalTextStyle.current,
    softWrap: Boolean = true,
    overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE,
    inlineContent: Map<String, InlineTextContent> = emptyMap(),
) {
    val requestedColor = color.takeUnless { it == Color.Unspecified }
        ?: style.color.takeUnless { it == Color.Unspecified } ?: LocalContentColor.current
    val surface = rememberReadableTextSurface(Color.Transparent, requestedColor)
    val readableText = remember(text, surface.backgrounds, surface.foreground) {
        text.withReadableSpans(surface.backgrounds, surface.foreground)
    }
    val options = if (LocalChatProseDecorationsEnabled.current) LocalChatInlineOptions.current else ChatInlineOptions()
    val plan = remember(text, options) { analyzeChatInline(text, options) }
    val styled = if (plan.quotes.isEmpty()) readableText else {
        val paletteSeed = currentTextPaletteSeed()
        val themeAccent = MaterialTheme.colorScheme.primary
        val quoteSeed = remember(paletteSeed, surface.foreground, themeAccent) {
            chatQuoteSeed(paletteSeed, surface.foreground, themeAccent)
        }
        remember(readableText, plan.quotes, surface.backgrounds, quoteSeed, fontWeight, style.fontWeight) {
            readableText.withChatQuoteHighlights(plan.quotes, surface.backgrounds, quoteSeed, fontWeight ?: style.fontWeight)
        }
    }
    val decorated = rememberHexInlineContent(styled, plan.hexSwatches, inlineContent,
        style.copy(color = surface.foreground, fontWeight = fontWeight ?: style.fontWeight))
    val showActions by rememberUpdatedState(LocalLinkActionHandler.current)
    val uriHandler by rememberUpdatedState(LocalUriHandler.current)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val hasLinks = remember(decorated.text) {
        val value = decorated.text
        value.getLinkAnnotations(0, value.length).isNotEmpty() || value.getStringAnnotations("URL", 0, value.length).isNotEmpty()
    }
    val interceptLinks = hasLinks && showActions != null
    val gesture = if (interceptLinks) Modifier.pointerInput(decorated.text) {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial)
            val target = layout?.linkAt(down.position) ?: return@awaitEachGesture
            down.consume()
            try {
                val up = withTimeout(viewConfiguration.longPressTimeoutMillis) {
                    waitForUpOrCancellation(PointerEventPass.Initial)
                }
                if (up != null && up.id == down.id && layout?.linkAt(up.position) == target) {
                    up.consume()
                    uriHandler.openUri(target.url)
                }
            } catch (_: PointerEventTimeoutCancellationException) {
                // Finish the touch sequence before a modal sheet takes focus.
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
                showActions?.invoke(target)
            }
        }
    } else Modifier
    val viewConfiguration = LocalViewConfiguration.current
    val linkConfiguration = remember(viewConfiguration) {
        object : ViewConfiguration by viewConfiguration {
            override val minimumTouchTargetSize = DpSize.Zero
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides if (interceptLinks) linkConfiguration else viewConfiguration) {
        Text(decorated.text, modifier.then(gesture), color = surface.foreground, fontWeight = fontWeight, style = style,
            softWrap = softWrap, overflow = overflow, maxLines = maxLines, inlineContent = decorated.inlineContent,
            onTextLayout = { layout = it })
    }
}
