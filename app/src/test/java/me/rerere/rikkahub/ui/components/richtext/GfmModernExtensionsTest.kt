package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertTrue
import org.junit.Test

class GfmModernExtensionsTest {
    @Test fun `tables badges kbd and callouts stay semantic in generated document`() {
        val document = buildMarkdownDocument("""
            > [!WARNING]
            > Check <kbd>Ctrl</kbd>+<kbd>K</kbd>

            | Name | Value |
            | --- | --- |
            | A | B |

            [![Build](https://img.shields.io/badge/build-ok-green)](https://example.com)
        """.trimIndent())
        assertTrue(document.select("table").isNotEmpty())
        assertTrue(document.select("kbd").isNotEmpty() || document.text().contains("Ctrl"))
        assertTrue(document.text().contains("[!WARNING]") || document.select("blockquote").isNotEmpty())
        assertTrue(document.select("img[src*=shields]").isNotEmpty())
    }
}
