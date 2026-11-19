package me.rerere.rikkahub.ui.components.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebViewAssetPolicyTest {
    @Test fun `only the claimed https asset origin is accepted`() {
        assertEquals("html/renderers/mermaid.min.js", localWebViewAssetPath("https", -1, "/assets/html/renderers/mermaid.min.js"))
        assertEquals("html/renderers/mermaid.min.js", localWebViewAssetPath("HTTPS", 443, "/assets/html/renderers/mermaid.min.js"))
        assertNull(localWebViewAssetPath("http", -1, "/assets/html/renderers/mermaid.min.js"))
        assertNull(localWebViewAssetPath("https", 8443, "/assets/html/renderers/mermaid.min.js"))
    }

    @Test fun `traversal and malformed paths never reach the asset loader`() {
        listOf(
            "/assets/../secret",
            "/assets/html/../../secret",
            "/assets/html\\secret.js",
            "/assets/html//secret.js",
            "/assets/html/\u0000secret.js",
            "/other/file.js",
        ).forEach { path -> assertNull(localWebViewAssetPath("https", -1, path)) }
    }

    @Test fun `renderer MIME types include wasm and geojson`() {
        assertEquals("application/wasm", webViewAssetMimeType("html/renderers/graphviz.wasm"))
        assertEquals("application/json", webViewAssetMimeType("html/maps/world.geojson"))
        assertEquals("text/javascript", webViewAssetMimeType("html/renderers/mermaid.min.js"))
    }
}
