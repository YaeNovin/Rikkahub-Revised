package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.ai.tools.RENDER_CONTENT_TOOL_NAME
import me.rerere.rikkahub.data.ai.tools.parseRenderRequest
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.ui.components.richtext.LatexText
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant

internal object RenderingToolUI : ToolUIRenderer {
    override val toolName = RENDER_CONTENT_TOOL_NAME
    @Composable override fun title(context: ToolUIContext): String =
        "渲染内容 · ${context.content.getStringContent("renderer") ?: context.arguments.getStringContent("renderer").orEmpty()}"

    override fun hasSummary(context: ToolUIContext): Boolean = context.content != null

    @Composable override fun Summary(context: ToolUIContext) {
        val request = remember(context.arguments, context.content) {
            if (context.content.getStringContent("status") == "accepted") runCatching { parseRenderRequest(context.arguments) }.getOrNull() else null
        }
        if (request == null) {
            context.content.getStringContent("error")?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            return
        }
        when (request.renderer.id) {
            "markdown", "annotated_text" -> MarkdownBlock(request.source, Modifier.fillMaxWidth())
            "latex" -> if (me.rerere.rikkahub.ui.context.LocalSettings.current.displaySetting.enableLatexRendering) {
                me.rerere.rikkahub.ui.components.richtext.MathBlock(request.source,
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize)
            } else HighlightCodeBlock(request.source, "latex", renderVisuals = false)
            else -> HighlightCodeBlock(request.source,
                language = if (request.renderer.id == "code") request.language else request.renderer.id,
                modifier = Modifier.fillMaxWidth(), renderVisuals = request.renderer.id != "code")
        }
    }

    @Composable override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        Column(Modifier.fillMaxHeight(0.8f).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title(context), style = MaterialTheme.typography.titleMedium)
            Summary(context)
        }
    }
}

/**
 * Render an accepted automatic artifact directly in the assistant answer.
 * The Tool part remains persisted for replay/export, but is not nested in the
 * chain-of-thought card anymore.
 */
@Composable
internal fun RenderingToolContent(tool: UIMessagePart.Tool) {
    val content = remember(tool.toolCallId, tool.output) {
        runCatching {
            JsonInstant.parseToJsonElement(
                tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            )
        }.getOrNull()
    }
    val context = remember(tool, content) {
        ToolUIContext(
            tool = tool,
            arguments = tool.inputAsJson(),
            content = content,
            loading = false,
        )
    }
    RenderingToolUI.Summary(context)
}
