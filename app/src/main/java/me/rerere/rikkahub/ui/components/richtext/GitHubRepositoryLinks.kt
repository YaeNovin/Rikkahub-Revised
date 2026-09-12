package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.runtime.*
import me.rerere.rikkahub.data.github.GitHubRepositoryUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node

internal val LocalGitHubCardsEnabled = compositionLocalOf { false }

/** Link semantics come from the existing Markdown parser, not a raw-URL regex. */
private fun eligibleGitHubLinks(document: Document) = document.select("a[href]")
    .asSequence()
    .filter { link -> link.select("img").isEmpty() && link.parents().none {
        it.normalName() in setOf("pre", "code", "kbd", "samp", "script", "style", "template", "svg")
    } }
    .mapNotNull { link -> GitHubRepositoryUrl.parse(link.attr("href"))?.let { link to it } }

internal fun gitHubRepositoryLinks(document: Document): List<String> = eligibleGitHubLinks(document)
    .map { it.second }
    .distinct()
    .take(6)
    .toList()

private const val REPOSITORY_CARD_ATTRIBUTE = "data-rikka-github-repository"

/** Decorate a copy: the shared parser cache and message source remain untouched. */
internal fun withGitHubRepositoryCards(document: Document): Document = document.clone().also { copy ->
    copy.select("[$REPOSITORY_CARD_ATTRIBUTE]").forEach { it.removeAttr(REPOSITORY_CARD_ATTRIBUTE) }
    eligibleGitHubLinks(copy).take(6).toList().forEach { (link, fullName) ->
        link.replaceWith(Element("span").attr(REPOSITORY_CARD_ATTRIBUTE, fullName).text(link.text()))
    }
}

internal fun Element.gitHubCardName(): String? =
    attr(REPOSITORY_CARD_ATTRIBUTE).takeIf(String::isNotEmpty)?.let(GitHubRepositoryUrl::canonicalFullName)

internal fun Node.containsGitHubCard(): Boolean = this is Element &&
    (gitHubCardName() != null || childNodes().any { it.containsGitHubCard() })

internal sealed interface GitHubInlineRun {
    data class Content(val nodes: List<Node>) : GitHubInlineRun
    data class Card(val fullName: String, val label: String) : GitHubInlineRun
}

/** Keep order and formatting around card boundaries, including lists and table cells. */
internal fun gitHubInlineRuns(nodes: List<Node>): List<GitHubInlineRun> {
    val result = mutableListOf<GitHubInlineRun>()
    fun append(run: GitHubInlineRun) {
        val previous = result.lastOrNull()
        if (previous is GitHubInlineRun.Content && run is GitHubInlineRun.Content) {
            result[result.lastIndex] = GitHubInlineRun.Content(previous.nodes + run.nodes)
        } else result += run
    }
    nodes.forEach { node ->
        val name = (node as? Element)?.gitHubCardName()
        when {
            name != null -> append(GitHubInlineRun.Card(name, (node as Element).text()))
            node is Element && node.containsGitHubCard() -> gitHubInlineRuns(node.childNodes()).forEach { run ->
                append(if (run is GitHubInlineRun.Content) GitHubInlineRun.Content(listOf(node.clone().empty().also { wrapper ->
                    run.nodes.forEach { wrapper.appendChild(it.clone()) }
                })) else run)
            }
            else -> append(GitHubInlineRun.Content(listOf(node)))
        }
    }
    return result
}
