package me.rerere.rikkahub.ui.components.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebViewContentCacheTest {
    @Test fun `corrupt cache is detected and repaired when reopening source`() {
        val id = WebViewContentCache.store(temporaryFolder.root, "<html>ok</html>")
        temporaryFolder.root.resolve("webview_content/$id").writeText("broken")
        assertNull(WebViewContentCache.load(temporaryFolder.root, id))
        WebViewContentCache.store(temporaryFolder.root, "<html>ok</html>")
        assertEquals("<html>ok</html>", WebViewContentCache.load(temporaryFolder.root, id))
    }
    @Test(expected = IllegalArgumentException::class)
    fun `oversized preview is rejected before creating a cache file`() {
        WebViewContentCache.store(temporaryFolder.root, "x".repeat(WebViewContentCache.MAX_CONTENT_BYTES + 1))
    }
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `stores large content outside navigation state`() {
        val content = "<html>${"preview".repeat(50_000)}</html>"

        val id = WebViewContentCache.store(temporaryFolder.root, content)

        assertEquals(64, id.length)
        assertEquals(content, WebViewContentCache.load(temporaryFolder.root, id))
    }

    @Test
    fun `reuses the same cache entry for identical content`() {
        val content = "<html>preview</html>"

        val firstId = WebViewContentCache.store(temporaryFolder.root, content)
        val secondId = WebViewContentCache.store(temporaryFolder.root, content)

        assertEquals(firstId, secondId)
        assertEquals(1, temporaryFolder.root.resolve("webview_content").listFiles()?.size)
    }

    @Test
    fun `rejects invalid cache ids`() {
        assertNull(WebViewContentCache.load(temporaryFolder.root, "../content"))
    }
}
