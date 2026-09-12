package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.material3.lightColorScheme
import me.rerere.rikkahub.ui.components.webview.shouldFinishStalledLoad
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class HtmlLoadingRegressionTest {
    @Test fun `stale timeout cannot stop a different page or a completed load`() {
        assertTrue(shouldFinishStalledLoad(true, true, true))
        assertFalse(shouldFinishStalledLoad(false, true, true))
        assertFalse(shouldFinishStalledLoad(true, false, true))
        assertFalse(shouldFinishStalledLoad(true, true, false))
    }
    @Test fun `preview keeps escaped fallback and only local code and styles`() {
        val template = File("src/main/assets/html/mark.html").readText()
        val markdown = "中文测试 <script>window.unexpected=true</script> {{BACKGROUND_COLOR}}"
        val html = buildMarkdownPreviewHtml(template, markdown, lightColorScheme())
        val document = Jsoup.parse(html)
        assertEquals(markdown, document.getElementById("preview-fallback")!!.wholeText())
        assertTrue(document.select("script[type=module]").isEmpty())
        assertTrue(document.select("script[src],link[href]").all {
            (it.attr("src").ifBlank { it.attr("href") }).startsWith("https://rikkahub.local/assets/")
        })
    }
    @Test fun `generate offline Markdown HTML regression sample`() {
        val template = File("src/main/assets/html/mark.html").readText()
        val markdown = """
            # Offline 中文预览

            - [x] Ready
            - [ ] Pending

            Inline ${'$'} x^2 + y^2 = z^2 ${'$'}.

            ${'$'}${'$'}
            \ce{H2O}
            ${'$'}${'$'}

            ```javascript
            const answer = 42;
            ```

            ```mermaid
            graph LR; A[开始] --> B[完成]
            ```

            <div><strong>HTML preserved</strong></div>
        """.trimIndent()
        File("build/reports/html-loading").apply { mkdirs() }
            .resolve("preview.html").writeText(buildMarkdownPreviewHtml(template, markdown, lightColorScheme()))
    }

    @Test fun `preview accepts direct and default plugin export compatibility`() {
        val template = File("src/main/assets/html/mark.html").readText()
        val html = buildMarkdownPreviewHtml(template, "# compatibility", lightColorScheme())
        assertTrue(html.contains("const unwrapPlugin = function(value)"))
        assertTrue(html.contains("typeof window.TextDecoder === 'function'"))
        assertTrue(html.contains("typeof window.matchMedia === 'function'"))
    }
}
