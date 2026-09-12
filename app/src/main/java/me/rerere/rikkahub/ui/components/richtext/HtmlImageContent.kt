package me.rerere.rikkahub.ui.components.richtext

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

internal sealed interface HtmlImageRun {
    data class Text(val nodes: List<Node>) : HtmlImageRun
    data class Image(val element: Element, val link: String?) : HtmlImageRun
}

internal fun Node.containsInlineImage(): Boolean = this is Element && when (normalName()) {
    "img" -> true
    "code", "pre", "script", "style" -> false
    else -> childNodes().any { it.containsInlineImage() }
}

/** Split at image boundaries, keeping text formatting, adjacent text and enclosing links intact.
 * Never mutate the cached Markdown document: table cells and exports reuse it. */
internal fun htmlImageRuns(nodes: List<Node>, link: String? = null): List<HtmlImageRun> {
    val runs = mutableListOf<HtmlImageRun>()
    fun append(run: HtmlImageRun) {
        val previous = runs.lastOrNull()
        if (previous is HtmlImageRun.Text && run is HtmlImageRun.Text) {
            runs[runs.lastIndex] = HtmlImageRun.Text(previous.nodes + run.nodes)
        } else runs += run
    }
    nodes.forEach { node ->
        when {
            node is Element && node.normalName() == "img" -> append(HtmlImageRun.Image(node, link))
            node is Element && node.containsInlineImage() -> {
                val target = if (node.normalName() == "a") node.attr("href") else link
                htmlImageRuns(node.childNodes(), target).forEach { run ->
                    append(when (run) {
                        is HtmlImageRun.Image -> run
                        is HtmlImageRun.Text -> HtmlImageRun.Text(listOf(node.clone().empty().also { wrapper ->
                            run.nodes.forEach { wrapper.appendChild(it.clone()) }
                        }))
                    })
                }
            }
            else -> append(HtmlImageRun.Text(listOf(node)))
        }
    }
    return runs
}

internal data class HtmlCallout(val title: String, val body: List<Node>)

/** GFM puts the marker and first content line in the SAME paragraph. Remove only the marker. */
internal fun htmlCallout(element: Element): HtmlCallout? {
    val first = element.children().firstOrNull()?.takeIf { it.normalName() == "p" } ?: return null
    val text = first.childNodes().firstOrNull() as? TextNode ?: return null
    val marker = Regex("^\\s*\\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)](?=\\s|$)", RegexOption.IGNORE_CASE)
        .find(text.wholeText) ?: return null
    val paragraph = first.clone()
    (paragraph.childNode(0) as TextNode).text(text.wholeText.drop(marker.range.last + 1).trimStart())
    // An explicit <br> may follow the marker; it is not part of the callout body.
    while (paragraph.childNodes().firstOrNull().let { it is TextNode && it.isBlank || it is Element && it.normalName() == "br" }) {
        paragraph.childNode(0).remove()
    }
    return HtmlCallout(marker.groupValues[1].uppercase(), element.childNodes().mapNotNull {
        when {
            it !== first -> it
            paragraph.childNodeSize() > 0 -> paragraph
            else -> null
        }
    })
}
