package me.rerere.rikkahub.ui.components.richtext

import me.rerere.rikkahub.ui.pages.webview.prepareFullscreenPreviewHtml
import me.rerere.rikkahub.ui.pages.webview.isReadonlyTerminalPreview
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Base64

class TerminalPreviewTest {
    @Test fun `terminal aliases are display only and require a complete bounded block`() {
        for (alias in listOf("ansi", "terminal", "xterm", "console-output", " LANGUAGE-ANSI ", "{.terminal}")) {
            assertTrue(canRenderTerminalPreview(alias, "echo hello", true))
        }
        for (language in listOf("bash", "sh", "shell", "console", "html", "plaintext", "javascript")) {
            assertFalse(canRenderTerminalPreview(language, "echo hello", true))
        }
        assertFalse(canRenderTerminalPreview("ansi", "partial", false))
        assertTrue(canRenderTerminalPreview("ansi", "a".repeat(MAX_TERMINAL_PREVIEW_CHARS), true))
        assertFalse(canRenderTerminalPreview("ansi", "a".repeat(MAX_TERMINAL_PREVIEW_CHARS + 1), true))
        assertTrue(runCatching { buildTerminalPreviewHtml("a".repeat(MAX_TERMINAL_PREVIEW_CHARS + 1)) }.isFailure)
    }

    @Test fun `SGR Unicode and progress updates are preserved`() {
        val transcript = "\u001b[31m错误\u001b[0m\n下载 10%\r\u001b[2K下载 100%\t✓🙂"
        assertEquals(transcript, terminalDisplayText(transcript))
        assertEquals("\u001b[32mOK\u001b[0m", terminalDisplayText("\\x1b[32mOK\\u001b[0m"))
        assertEquals("错误\n下载 10%\r下载 100%\t✓🙂", terminalDisplayText(transcript, keepAnsi = false))
    }

    @Test fun `OSC clipboard links titles and bells never reach xterm`() {
        assertEquals("AvisibleB", terminalDisplayText("A\u001b]52;c;secret\u0007\u001b]8;;https://evil.test\u001b\\visible\u001b]8;;\u0007\u001b]0;title\u0007B\u0007"))
        assertEquals("ok", terminalDisplayText("ok\u001b]52;c;unterminated"))
        assertEquals("ok", terminalDisplayText("\u009d52;c;secret\u009cok"))
    }

    @Test fun `DCS graphics private modes device queries and escape strings are dropped`() {
        for (prefix in listOf("\u001bP", "\u001b_", "\u001b^", "\u001bX", "\u0090", "\u0098", "\u009e", "\u009f")) {
            assertEquals("AB", terminalDisplayText("A${prefix}not text\u001b\\B"))
            assertEquals("A", terminalDisplayText("A${prefix}unterminated"))
        }
        assertEquals("text", terminalDisplayText("\u001b[?1000h\u001b[6n\u001b[18t\u001bctext"))
        assertEquals("\u001b[38;2;255;0;0mred", terminalDisplayText("\u009b38;2;255;0;0mred"))
        assertEquals("AB", terminalDisplayText("A\u0000\u0001\u007f\u0080B"))
    }

    @Test fun `HTML and scripts in terminal output remain encoded data`() {
        val source = "</script><script>window.__injected = true</script><img src=https://evil.test> & 中文"
        val html = buildTerminalPreviewHtml(source)
        val doc = Jsoup.parse(html)
        assertEquals(source, String(Base64.getDecoder().decode(doc.body().attr("data-source")), Charsets.UTF_8))
        assertFalse(html.contains(source))
        assertTrue(doc.select("img,iframe,script:not([src])").isEmpty())
        assertEquals(3, doc.select("script[src]").size)
        assertTrue(doc.select("script[src]").all { it.attr("src").startsWith("https://rikkahub.local/assets/html/renderers/") })
        val csp = doc.selectFirst("meta[http-equiv=Content-Security-Policy]")!!.attr("content")
        assertTrue(csp.contains("connect-src 'none'"))
        assertFalse(csp.substringAfter("script-src ").substringBefore(';').contains("unsafe-inline"))
        assertFalse(html.contains("addJavascriptInterface"))
    }

    @Test fun `fullscreen preserves terminal viewport and strict CSP without diagram scripts`() {
        val html = buildTerminalPreviewHtml("hello")
        assertTrue(isReadonlyTerminalPreview(html))
        assertFalse(isReadonlyTerminalPreview("<html><body>terminal-readonly</body></html>"))
        assertFalse(isReadonlyTerminalPreview("<html><body><svg/></body></html>"))
        val fullscreen = prepareFullscreenPreviewHtml(html)
        assertEquals("false", Jsoup.parse(fullscreen).body().attr("data-embedded"))
        assertEquals(Jsoup.parse(html).body().attr("data-source"), Jsoup.parse(fullscreen).body().attr("data-source"))
        assertEquals(fullscreen, prepareFullscreenPreviewHtml(html, useGlobalBackground = true))
    }

    @Test fun `vendor assets match the pinned MIT distributions`() {
        val hashes = mapOf(
            "renderers/xterm-6.0.0.js" to "14903579ff54664cd72f8e8699e6961a6272c21863ec1c3b118cdc8af5d4a972",
            "renderers/xterm-6.0.0.css" to "854a7c0fb70e8b1a083c16797ab827299fb18744f5ad34f227b48337e33293c6",
            "renderers/xterm-addon-fit-0.11.0.js" to "ba3ea256ce0620a0992a197d6c9baea64823fc93d8da07a9e366ca9943c18527",
        )
        hashes.forEach { (name, expected) ->
            val actual = MessageDigest.getInstance("SHA-256").digest(File("src/main/assets/html/$name").readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(name, expected, actual)
        }
        listOf("xterm-6.0.0-MIT.txt", "xterm-addon-fit-0.11.0-MIT.txt").forEach {
            assertTrue(File("src/main/assets/html/licenses/$it").readText().contains("Permission is hereby granted"))
        }
    }

    @Test fun `generate production HTML for browser regression`() {
        val output = File("build/reports/terminal-preview").apply { mkdirs() }
        val sample = "\u001b[32mSUCCESS\u001b[0m 中文 🙂\r\n" +
            "progress 10%\r\u001b[2Kprogress 100%\r\n" +
            "\u001b[1;31mBOLD\u001b[0m \u001b[38;5;208mORANGE\u001b[0m \u001b[38;2;121;192;255mRGB\u001b[0m\r\n" +
            "</script><script>window.__injected = true</script>\r\n" +
            "\u001b]8;;https://evil.test\u0007visible link\u001b]8;;\u0007\r\n" +
            (1..80).joinToString("\r\n") { "line $it: terminal output" }
        output.resolve("dark.html").writeText(buildTerminalPreviewHtml(sample))
        output.resolve("light.html").writeText(buildTerminalPreviewHtml(sample, dark = false))
        output.resolve("fullscreen.html").writeText(prepareFullscreenPreviewHtml(buildTerminalPreviewHtml(sample)))
        output.resolve("empty.html").writeText(buildTerminalPreviewHtml(""))
        output.resolve("long.html").writeText(buildTerminalPreviewHtml("\u001b[31m" + "x".repeat(100_000)))
    }
}
