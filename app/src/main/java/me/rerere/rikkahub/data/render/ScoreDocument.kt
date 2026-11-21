package me.rerere.rikkahub.data.render

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

internal const val MAX_SCORE_SOURCE_CHARS = 512 * 1024
internal val scoreRendererIds = setOf("abc", "jianpu", "musicxml", "mei")

/** Score data only. No schema/DTD fetches, external entities, archive extraction or WebView file access. */
internal data class ScoreDocument(val renderer: String, val source: String)

internal object ScoreDocuments {
    private const val MAX_BYTES = 2 * 1024 * 1024
    private val extensions = setOf("abc", "jianpu", "musicxml", "mxl", "mei", "xml")
    private val musicMimes = setOf("application/vnd.recordare.musicxml", "application/vnd.recordare.musicxml+xml", "application/mei+xml")
    fun isCandidate(name: String, mime: String = ""): Boolean =
        name.substringAfterLast('.', "").lowercase() in extensions ||
            mime.substringBefore(';').lowercase() in musicMimes

    fun read(file: File, name: String, mime: String = ""): ScoreDocument? {
        require(file.isFile && file.length() <= MAX_BYTES) { "乐谱文件不存在或超过 2 MiB" }
        val bytes = file.inputStream().use { it.readNBytesCompat(MAX_BYTES) }
        return decode(bytes, name, mime)
    }

    internal fun decode(bytes: ByteArray, name: String, mime: String = ""): ScoreDocument? {
        require(bytes.size <= MAX_BYTES) { "乐谱超过 2 MiB" }
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension == "mxl" || mime.substringBefore(';').lowercase() == "application/vnd.recordare.musicxml") {
            return parseXml(unpack(bytes), "musicxml")
        }
        val source = decodeText(bytes)
        return when (extension) {
            "abc", "jianpu" -> ScoreDocument(extension, source.also {
                require(it.isNotBlank()) { "乐谱为空" }
                if (extension == "abc") require(it.length <= 128 * 1024) { "ABC 超过 128 Ki 字符" }
            })
            "mei" -> parseXml(source, "mei")
            "musicxml" -> parseXml(source, "musicxml")
            else -> {
                val document = xml(source)
                when (document.documentElement.localName) {
                    "score-partwise" -> parseXml(source, "musicxml")
                    "mei" -> parseXml(source, "mei")
                    else -> null // Ordinary XML attachments retain their existing external-open behavior.
                }
            }
        }
    }

    fun parseXml(source: String, renderer: String): ScoreDocument {
        require(renderer in setOf("musicxml", "mei")) { "未知乐谱格式" }
        val clean = cleanXml(source)
        val document = xml(clean)
        val root = document.documentElement
        require(if (renderer == "musicxml") root.localName == "score-partwise" else
            root.localName == "mei" && root.namespaceURI == "http://www.music-encoding.org/ns/mei") {
            if (renderer == "musicxml") "目前支持 score-partwise MusicXML，暂不支持 score-timewise" else "需要带 MEI 命名空间的 mei 文档"
        }
        if (renderer == "mei") require(root.getElementsByTagNameNS("*", "mdiv").length == 1) { "目前每份 MEI 需包含一个 mdiv，多个乐章请分文件预览" }
        val elements = document.getElementsByTagName("*")
        require(elements.length <= 40000) { "乐谱 XML 节点过多" }
        var notes = 0
        for (i in 0 until elements.length) {
            val element = elements.item(i) as Element
            if (element.localName in setOf("note", "rest", "nc")) notes++
            var depth = 0; var ancestor = element.parentNode
            while (ancestor != null && depth <= 64) { depth++; ancestor = ancestor.parentNode }
            require(depth <= 64) { "乐谱 XML 嵌套过深" }
            require(element.localName !in setOf("script", "image", "graphic", "binaryObject", "xi:include", "include")) { "乐谱不加载外部资源" }
            for (j in 0 until element.attributes.length) {
                val attr = element.attributes.item(j)
                require(!attr.nodeName.startsWith("on", true)) { "乐谱不接受事件脚本" }
                if (attr.localName in setOf("href", "target", "facs", "source")) {
                    require(attr.nodeValue.split(Regex("\\s+")).all { it.isBlank() || it.startsWith('#') }) { "乐谱不加载外部引用" }
                }
            }
        }
        require(notes in 1..16000) { "乐谱需包含音符或休止符，最多 16000 个事件" }
        if (renderer == "musicxml") {
            val parts = root.getElementsByTagName("part")
            require(parts.length in 1..24) { "乐谱最多 24 个声部" }
            for (i in 0 until parts.length) require((parts.item(i) as Element).getElementsByTagName("measure").length <= 512) { "每个声部最多 512 小节" }
        }
        return ScoreDocument(renderer, clean)
    }

    // Standard MusicXML PUBLIC/SYSTEM declarations are common in notation exports.
    // Remove only their inert external declaration; never accept internal subsets/entities.
    internal fun cleanXml(source: String): String {
        require(source.length <= MAX_SCORE_SOURCE_CHARS) { "乐谱超过 512 Ki 字符" }
        val clean = source.removePrefix("\uFEFF").replace(Regex("""<!DOCTYPE\s+score-partwise\s+(?:PUBLIC\s+"[^"<>\[\]]*"\s+"[^"<>\[\]]*"|SYSTEM\s+"[^"<>\[\]]*")\s*>"""), "")
        require(!Regex("<!DOCTYPE|<!ENTITY", RegexOption.IGNORE_CASE).containsMatchIn(clean)) { "不支持 DTD 内部子集或实体声明" }
        return clean
    }

    private fun xml(source: String): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            // Android's parser already disables external DTD loading via the entity resolver.
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
            setErrorHandler(object : DefaultHandler() {
                override fun error(e: SAXParseException) { throw e }
                override fun fatalError(e: SAXParseException) { throw e }
            })
        }
        return builder.parse(InputSource(StringReader(cleanXml(source))))
    }

    private fun unpack(bytes: ByteArray): String {
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0; var count = 0
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= 64) { "MXL 压缩包条目过多" }
                require(entry.name.isNotBlank() && !entry.name.startsWith('/') && '\\' !in entry.name && ':' !in entry.name &&
                    entry.name.trimEnd('/').split('/').none { it == ".." || it == "." || it.isEmpty() }) { "MXL 包含不安全路径" }
                if (entry.isDirectory) continue
                require(entry.name !in entries) { "MXL 包含重复路径" }
                val data = zip.readNBytesCompat(MAX_BYTES - total)
                total += data.size; entries[entry.name] = data
            }
        }
        val container = entries["META-INF/container.xml"] ?: throw IllegalArgumentException("MXL 缺少 META-INF/container.xml")
        val roots = xml(decodeText(container)).getElementsByTagNameNS("*", "rootfile")
        val root = (0 until roots.length).map { roots.item(it) as Element }.firstOrNull {
            it.getAttribute("media-type") == "application/vnd.recordare.musicxml+xml"
        } ?: throw IllegalArgumentException("MXL 缺少 MusicXML 主文件声明")
        return decodeText(entries[root.getAttribute("full-path")] ?: throw IllegalArgumentException("MXL 主文件不存在"))
    }

    private fun decodeText(bytes: ByteArray): String {
        val utf16le = bytes.size >= 2 && (bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() || bytes[0] == '<'.code.toByte() && bytes[1] == 0.toByte())
        val utf16be = bytes.size >= 2 && (bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() || bytes[0] == 0.toByte() && bytes[1] == '<'.code.toByte())
        val charset = when { utf16le -> Charsets.UTF_16LE; utf16be -> Charsets.UTF_16BE; else -> Charsets.UTF_8 }
        val text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        require(text.length <= MAX_SCORE_SOURCE_CHARS) { "乐谱超过 512 Ki 字符" }
        // The renderer receives a Unicode string, not the original byte encoding.
        return text.replaceFirst(Regex("""(<\?xml\s[^?]*?)encoding\s*=\s*['"][^'"]+['"]"""), "$1encoding=\"UTF-8\"")
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer); if (n < 0) break
            require(output.size() + n <= limit) { "乐谱或 MXL 展开后超过 2 MiB" }
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }
}
