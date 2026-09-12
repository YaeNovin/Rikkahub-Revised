package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.*
import org.junit.Test

class GitHubRepositoryLinksTest {
    @Test fun `screenshot bare and named links become cards at their original positions while badge stays an image`() {
        val original = buildMarkdownDocument("""
            ## First
            https://github.com/YaeNovin/Rikkahub-Revised

            ## Second
            [YaeNovin/Rikkahub-Revised](https://github.com/YaeNovin/Rikkahub-Revised)

            ## Third
            [![badge](https://img.shields.io/badge/GitHub-repo-purple)](https://github.com/YaeNovin/Rikkahub-Revised)
        """.trimIndent())
        val before = original.outerHtml()
        val decorated = withGitHubRepositoryCards(original)
        val blocks = decorated.body().children()
        assertEquals("h2", blocks[0].tagName())
        assertEquals(1, blocks[1].select("[data-rikka-github-repository]").size)
        assertEquals("h2", blocks[2].tagName())
        assertEquals(1, blocks[3].select("[data-rikka-github-repository]").size)
        assertEquals(1, blocks[5].select("a > img").size)
        assertEquals(2, decorated.select("[data-rikka-github-repository]").size)
        assertEquals(before, original.outerHtml())
    }

    @Test fun `inline card runs retain surrounding formatted content and references in tables`() {
        val decorated = withGitHubRepositoryCards(buildMarkdownDocument("**Before [project](https://github.com/owner/repo) after**"))
        val runs = gitHubInlineRuns(decorated.selectFirst("p")!!.childNodes())
        assertEquals(3, runs.size)
        assertTrue((runs[0] as GitHubInlineRun.Content).nodes[0].outerHtml().contains("<strong>Before"))
        assertEquals("owner/repo", (runs[1] as GitHubInlineRun.Card).fullName)
        assertEquals("project", (runs[1] as GitHubInlineRun.Card).label)
        assertTrue((runs[2] as GitHubInlineRun.Content).nodes[0].outerHtml().contains("after</strong>"))
        val table = withGitHubRepositoryCards(buildMarkdownDocument("| Repo |\n| --- |\n| [project][r] |\n\n[r]: https://github.com/owner/repo"))
        assertTrue(table.selectFirst("td")!!.containsGitHubCard())
    }

    @Test fun `decoration respects card limit and source cannot forge native cards`() {
        val decorated = withGitHubRepositoryCards(buildMarkdownDocument((1..8).joinToString("\n\n") { "https://github.com/owner/repo$it" }))
        assertEquals(6, decorated.select("[data-rikka-github-repository]").size)
        assertEquals(2, decorated.select("a[href]").size)
        val forged = buildMarkdownDocument("<span data-rikka-github-repository='owner/repo'>fake</span>")
        assertFalse(forged.body().containsGitHubCard())
    }
    @Test fun `plain named reference HTML and table links are recognized without losing text`() {
        val source = """
            See https://github.com/owner/plain and [a project](https://github.com/owner/named).

            <div>Mixed <a href="https://github.com/owner/html">HTML link</a></div>

            [reference][repo]

            [repo]: https://github.com/owner/ref

            | Name | Repo |
            | --- | --- |
            | Example | [Table](https://github.com/owner/table) |
        """.trimIndent()
        val doc = buildMarkdownDocument(source)
        assertEquals(listOf("owner/plain", "owner/named", "owner/html", "owner/ref", "owner/table"), gitHubRepositoryLinks(doc))
        assertTrue(doc.text().contains("Mixed HTML link"))
        assertEquals(1, doc.select("table").size)
    }

    @Test fun `examples images badges code subpaths and unrelated hosts do not become repo cards`() {
        val doc = buildMarkdownDocument("""
            ```markdown
            [Code example](https://github.com/owner/code)
            ```
            `[Inline code](https://github.com/owner/inline)`

            ![Image](https://github.com/owner/image)
            [![Badge](https://img.shields.io/badge/build-ok-green)](https://github.com/owner/badge)

            <pre><a href="https://github.com/owner/pre">Example</a></pre>
            [Issue](https://github.com/owner/repo/issues/1) [PR](https://github.com/owner/repo/pull/1)
            [Source](https://github.com/owner/repo/blob/main/README.md) [Profile](https://github.com/owner)
            [Fake](https://github.com.evil.test/owner/repo)
        """.trimIndent())
        assertEquals(emptyList<String>(), gitHubRepositoryLinks(doc))
    }

    @Test fun `case variants de duplicate and many repositories have a per message limit`() {
        val doc = buildMarkdownDocument("https://github.com/OWNER/Repo https://github.com/owner/repo/\n\n" +
            (1..10).joinToString("\n\n") { "https://github.com/owner/repo$it" })
        assertEquals(listOf("owner/repo") + (1..5).map { "owner/repo$it" }, gitHubRepositoryLinks(doc))
    }
}
