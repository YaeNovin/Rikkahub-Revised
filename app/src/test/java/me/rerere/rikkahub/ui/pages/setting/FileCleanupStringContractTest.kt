package me.rerere.rikkahub.ui.pages.setting

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

class FileCleanupStringContractTest {
    @Test fun `every translated cleanup result preserves the argument contract and displays counts`() {
        val resources = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val parser = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder()
        val names = mapOf(
            "setting_files_page_clean_failed_toast" to arrayOf<Any>(17, 29),
            "setting_files_page_cleaned_toast" to arrayOf<Any>(31, 13, 18),
        )
        val formats = mutableMapOf<String, MutableMap<String, String>>()
        resources.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("values") }.forEach { directory ->
            val file = File(directory, "strings.xml")
            if (!file.isFile) return@forEach
            val strings = parser.parse(file).getElementsByTagName("string")
            for (index in 0 until strings.length) {
                val node = strings.item(index)
                val name = node.attributes.getNamedItem("name").nodeValue
                if (name in names) formats.getOrPut(directory.name) { mutableMapOf() }[name] = node.textContent
            }
        }
        val placeholders = Regex("%[0-9]+[${'$'}][a-zA-Z]")
        assertTrue(formats.keys.containsAll(listOf("values", "values-zh", "values-zh-rTW", "values-ja", "values-ko-rKR", "values-ru")))
        formats.forEach { (locale, strings) -> names.forEach { (name, args) ->
            val text = requireNotNull(strings[name]) { "$locale is missing $name" }
            val expected = placeholders.findAll(formats.getValue("values").getValue(name)).map { it.value }.sorted().toList()
            assertEquals("$locale/$name argument types and positions", expected,
                placeholders.findAll(text).map { it.value }.sorted().toList())
            val rendered = String.format(Locale.ROOT, text, *args)
            args.forEach { assertTrue("$locale/$name must display count $it", rendered.contains(it.toString())) }
        } }
    }
}
