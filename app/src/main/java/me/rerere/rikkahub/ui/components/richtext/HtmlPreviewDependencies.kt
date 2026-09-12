package me.rerere.rikkahub.ui.components.richtext

import me.rerere.rikkahub.ui.components.webview.WEB_VIEW_ASSET_URL
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.util.Locale

/** Browser globals supported in executable HTML, in dependency order. */
private enum class HtmlPreviewLibrary(
    val global: String,
    val fileName: String,
    val packageName: String,
    val dependencies: List<HtmlPreviewLibrary> = emptyList(),
) {
    VEGA("vega", "vega.min.js", "vega"),
    VEGA_LITE("vegaLite", "vega-lite.min.js", "vega-lite", listOf(VEGA)),
    VEGA_EMBED("vegaEmbed", "vega-embed.js", "vega-embed", listOf(VEGA, VEGA_LITE)),
    SMILES("SmilesDrawer", "smiles-drawer.js", "smiles-drawer"),
    MUSIC_XML("opensheetmusicdisplay", "opensheetmusicdisplay.js", "opensheetmusicdisplay"),
    GRAPHVIZ("hpccWasm", "graphviz-compat.js", "@hpcc-js/wasm-graphviz");

    // Inspect executable script text only, never prose, code examples or JSON data blocks.
    val reference = Regex("(?<![A-Za-z0-9_$])${Regex.escape(global)}(?![A-Za-z0-9_$])")
    val fileAliases: Set<String> = setOf(fileName, "$packageName.js", "$packageName.min.js", "graphviz.min.js")
}

private val SCRIPT_TYPES = setOf("", "text/javascript", "application/javascript", "text/ecmascript", "application/ecmascript")
private val RENDERER_CDN_HOSTS = setOf("cdn.jsdelivr.net", "fastly.jsdelivr.net", "gcore.jsdelivr.net", "unpkg.com", "cdnjs.cloudflare.com")
private const val DEPENDENCY_MARKER = "data-rikkahub-renderer-library"
private const val DEPENDENCY_GUARD_ID = "rikkahub-renderer-dependency-guard"

private fun isClassicScript(script: Element): Boolean =
    script.attr("type").trim().lowercase(Locale.ROOT) in SCRIPT_TYPES

private fun localPreviewLibrary(source: String): HtmlPreviewLibrary? {
    val uri = runCatching { URI(source.trim()) }.getOrNull() ?: return null
    val path = uri.path?.lowercase(Locale.ROOT) ?: return null
    val host = uri.host?.lowercase(Locale.ROOT)
    val library = HtmlPreviewLibrary.entries.firstOrNull { path.substringAfterLast('/') in it.fileAliases }
    if (host == "rikkahub.local" || (host == null && uri.scheme == null && uri.rawAuthority == null)) {
        // Known app asset paths and bare filenames often appear in model-written HTML.
        return library?.takeIf {
            path == path.substringAfterLast('/') || path.startsWith("/assets/html/") || path.startsWith("assets/html/")
        }
    }
    if (uri.scheme != null && uri.scheme !in setOf("http", "https")) return null
    if (host !in RENDERER_CDN_HOSTS) return null
    val segments = path.trim('/').split('/')
    val packageIndex = when (host) {
        "cdnjs.cloudflare.com" -> if (segments.take(2) == listOf("ajax", "libs")) 2 else return null
        "unpkg.com" -> 0
        else -> if (segments.firstOrNull() == "npm") 1 else return null
    }
    val packageName = segments.getOrNull(packageIndex)?.substringBefore('@') ?: return null
    val candidate = HtmlPreviewLibrary.entries.firstOrNull {
        it.packageName == packageName || (it == HtmlPreviewLibrary.SMILES && packageName == "smilesdrawer")
    } ?: if (packageName in setOf("@hpcc-js/wasm-graphviz", "@hpcc-js/wasm-graphviz-next")) HtmlPreviewLibrary.GRAPHVIZ else return null
    // Only browser bundles/package roots, not ES modules, plugins or arbitrary same-name files.
    val suffix = segments.drop(packageIndex + 1)
    return candidate.takeIf {
        suffix.isEmpty() ||
            (host == "cdnjs.cloudflare.com" && suffix.size == 2 && suffix.last() in it.fileAliases) ||
            (suffix.size == 1 && suffix.last() in it.fileAliases) ||
            (suffix.size == 2 && suffix.first() in setOf("build", "dist") && suffix.last() in it.fileAliases)
    }
}

/**
 * Raw HTML has a separate path from typed diagram fences. Supply only its requested browser
 * globals, before author scripts run. Do not execute inert scripts or rewrite module imports,
 * unknown URLs, local user data, or scripts in templates.
 */
internal fun installHtmlPreviewDependencies(document: Document) {
    val scripts = document.select("script").filter { script ->
        isClassicScript(script) && script.parents().none { it.normalName() == "template" }
    }
    val needed = linkedSetOf<HtmlPreviewLibrary>()
    fun requireLibrary(library: HtmlPreviewLibrary) {
        library.dependencies.forEach(::requireLibrary)
        needed += library
    }
    scripts.forEach { script ->
        if (script.hasAttr("src")) {
            localPreviewLibrary(script.attr("src"))?.let { library ->
                requireLibrary(library)
                // Rebuild a blocking local tag: remote SRI, async/defer and duplicate bundles
                // must not override globals or race the following inline initialization.
                script.remove()
            }
        } else if (script.id() != DEPENDENCY_GUARD_ID) {
            HtmlPreviewLibrary.entries.filter { it.reference.containsMatchIn(script.data()) }.forEach(::requireLibrary)
        }
    }
    if (needed.isEmpty()) return
    document.getElementById(DEPENDENCY_GUARD_ID)?.remove()
    val dependencyTags = HtmlPreviewLibrary.entries.filter { it in needed }.map { library ->
        Element("script")
            .attr("src", "$WEB_VIEW_ASSET_URL/html/renderers/${library.fileName}")
            .attr(DEPENDENCY_MARKER, library.global)
    }
    val guard = Element("script").attr("id", DEPENDENCY_GUARD_ID).append(
        """
        (function() {
            var failed = [];
            function report() {
                if (!failed.length || !document.body) return;
                var notice = document.getElementById('rikkahub-renderer-dependency-error');
                if (!notice) {
                    notice = document.createElement('div');
                    notice.id = 'rikkahub-renderer-dependency-error';
                    notice.setAttribute('role', 'alert');
                    notice.style.cssText = 'padding:12px;margin:8px;border:1px solid #a15c00;border-radius:8px;background:#fff3d6;color:#563300;font:14px/1.5 sans-serif;white-space:normal;';
                    document.body.insertBefore(notice, document.body.firstChild);
                }
                notice.textContent = 'HTML 图形依赖加载失败：' + failed.join('、') + '。请重试，或更新 Android System WebView。其他内容仍可查看。';
            }
            window.addEventListener('error', function(event) {
                var target = event.target;
                var name = target && target.getAttribute && target.getAttribute('$DEPENDENCY_MARKER');
                if (name && failed.indexOf(name) < 0) { failed.push(name); report(); }
            }, true);
            document.addEventListener('DOMContentLoaded', function() {
                document.querySelectorAll('script[$DEPENDENCY_MARKER]').forEach(function(script) {
                    var name = script.getAttribute('$DEPENDENCY_MARKER');
                    if (typeof window[name] === 'undefined' && failed.indexOf(name) < 0) failed.push(name);
                });
                report();
            });
        })();
        """.trimIndent(),
    )
    // Keep early charset, base and CSP metadata in place; run before the first author script.
    val firstScript = document.head().children().firstOrNull { it.normalName() == "script" }
    (listOf(guard) + dependencyTags).forEach { tag ->
        if (firstScript != null) firstScript.before(tag) else document.head().appendChild(tag)
    }
}
