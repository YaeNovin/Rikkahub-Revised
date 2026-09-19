package me.rerere.rikkahub.ui.components.richtext

import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserMessageSourcePreserverTest {
    @Test
    fun `bare pasted source stays intact across common languages`() {
        val samples = linkedMapOf(
            "c" to "#include <stdio.h>\nint main(void) {\n    printf(\"hello\\n\");\n}",
            "cpp" to "#include <iostream>\nstd::vector<int> values = {1, 2};\nstd::cout << values[0];",
            "csharp" to "public static List<string> Names = new();\nreturn Names.Count > 0;",
            "java" to "public class Demo<T> {\n    List<String> values = new ArrayList<>();\n}",
            "kotlin" to "fun <T> identity(value: T): T {\n    return value\n}",
            "go" to "package main\nfunc main() {\n    fmt.Println(\"hello\")\n}",
            "rust" to "fn main() {\n    let values: Vec<i32> = vec![1, 2];\n}",
            "swift" to "func greet(name: String) -> String {\n    return \"Hello \\(name)\"\n}",
            "dart" to "void main() {\n  final values = <int>[1, 2];\n}",
            "javascript" to "const add = (a, b) => a + b;\nconsole.log(add(1, 2));",
            "typescript" to "const identity = <T>(value: T): T => value;\nconst names: Array<string> = [];",
            "python" to "def greet(name):\n    # keep this comment\n    return f\"Hello {name}\"",
            "ruby" to "def greet(name)\n  puts \"Hello #{name}\"\nend",
            "php" to "<?php\nfunction greet(string \$name): string {\n    return \"Hello \$name\";\n}",
            "lua" to "local function greet(name)\n    print(\"Hello \" .. name)\nend",
            "perl" to "my \$name = \"world\";\nprint \"Hello \$name\\n\";",
            "bash" to "#!/usr/bin/env bash\n# keep this comment\nfor file in *.txt; do\n  echo \"\$file\"\ndone",
            "powershell" to "param([string]\$Name)\n\$items = Get-ChildItem\nWrite-Host \"Hello \$Name\"",
            "sql" to "SELECT id, name\nFROM users\nWHERE active = 1\nORDER BY name;",
            "r" to "square <- function(x) {\n  x ** 2\n}\nprint(square(4))",
            "groovy" to "def greet(String name) {\n    println \"Hello \$name\"\n}",
            "scala" to "object Demo {\n  def identity[T](value: T): T = value\n}",
        )

        samples.forEach { (language, source) ->
            val document = buildMarkdownDocument(source, MarkdownRenderMode.USER_MESSAGE)
            val codeBlocks = document.select("pre code")
            assertEquals("$language should be rendered as one source block", 1, codeBlocks.size)
            assertEquals(language, source, codeBlocks.single().wholeText().trimEnd('\n'))
        }
    }

    @Test
    fun `crlf and multiple separately pasted snippets keep their source lines`() {
        val source = """
            Java:
            public class Demo<T> {
                List<String> values = new ArrayList<>();
            }

            Python:
            def greet(name):
                # user comment
                return f"Hello {name}"

            Shell:
            #!/usr/bin/env bash
            echo "${'$'}HOME"
        """.trimIndent().replace("\n", "\r\n")

        val document = buildMarkdownDocument(source, MarkdownRenderMode.USER_MESSAGE)
        val blocks = document.select("pre code").map { it.wholeText().trimEnd('\n') }

        assertEquals(3, blocks.size)
        assertTrue(blocks[0].contains("Demo<T>"))
        assertTrue(blocks[0].contains("List<String>"))
        assertTrue(blocks[1].contains("# user comment\n    return"))
        assertTrue(blocks[2].contains("#!/usr/bin/env bash\necho \"${'$'}HOME\""))
    }

    @Test
    fun `existing fenced source is not wrapped or escaped again`() {
        val source = """
            ```cpp
            #include <iostream>
            std::vector<int> values;
            ```
        """.trimIndent()

        val preprocessed = preprocessMarkdownContent(source, MarkdownRenderMode.USER_MESSAGE)
        val document = buildMarkdownDocument(source, MarkdownRenderMode.USER_MESSAGE)

        assertEquals(source, preprocessed)
        assertEquals(1, document.select("pre code").size)
        assertEquals(
            "#include <iostream>\nstd::vector<int> values;",
            document.selectFirst("pre code")!!.wholeText().trimEnd('\n'),
        )
    }

    @Test
    fun `real html and markdown remain active while generic types remain visible`() {
        val source = "# Heading\n\n- item\n\n~~gone~~ <s>old</s> <u>under</u> <small>small</small> <https://example.com> List<String> Demo<T>"
        val document = buildMarkdownDocument(source, MarkdownRenderMode.USER_MESSAGE)

        assertEquals("Heading", document.selectFirst("h1")!!.text())
        assertEquals("item", document.selectFirst("li")!!.text())
        assertEquals("gone", document.selectFirst("span.user-del")!!.text())
        assertEquals("old", document.selectFirst("s")!!.text())
        assertEquals("under", document.selectFirst("u")!!.text())
        assertEquals("small", document.selectFirst("small")!!.text())
        assertEquals("https://example.com", document.selectFirst("a[href]")!!.attr("href"))
        assertTrue(document.body().wholeText().contains("List<String> Demo<T>"))
    }

    @Test
    fun `unclosed inline html is displayed literally instead of leaking style`() {
        val source = "before <u>open\nafter"
        val document = buildMarkdownDocument(source, MarkdownRenderMode.USER_MESSAGE)

        assertTrue(document.select("u").isEmpty())
        assertTrue(document.body().wholeText().contains("<u>open"))
        assertTrue(document.body().wholeText().contains("after"))
    }

    @Test
    fun `html break consumes only its parser generated source newline`() {
        val afterBreak = TextNode("\r\nnext\nkept")

        assertEquals("next\nkept", textNodeContent(afterBreak, preserveSoftLineBreaks = true, dropLeadingBreak = true))
        assertEquals("\r\nnext\nkept", textNodeContent(afterBreak, preserveSoftLineBreaks = true))
    }

    @Test
    fun `ordered html lists retain their requested start`() {
        assertEquals(3, orderedListStart(Element("ol").attr("start", "3")))
        assertEquals(1, orderedListStart(Element("ol").attr("start", "invalid")))
    }

    @Test
    fun `inline code document retains consecutive whitespace`() {
        val document = buildMarkdownDocument(
            "before <code>alpha  beta\ngamma</code> after",
            MarkdownRenderMode.USER_MESSAGE,
        )

        assertEquals("alpha  beta\ngamma", document.selectFirst("code")!!.wholeText())
    }

    @Test
    fun `standard and user documents use isolated cache entries`() {
        val source = "#include <iostream>"
        val standard = preloadMarkdownDocument(source, MarkdownRenderMode.STANDARD)
        val user = preloadMarkdownDocument(source, MarkdownRenderMode.USER_MESSAGE)

        assertNotEquals(standard.body().wholeText(), user.body().wholeText())
        assertFalse(standard.body().wholeText().contains("<iostream>"))
        assertTrue(user.body().wholeText().contains("#include <iostream>"))
        assertTrue(isMarkdownDocumentCached(source, MarkdownRenderMode.STANDARD))
        assertTrue(isMarkdownDocumentCached(source, MarkdownRenderMode.USER_MESSAGE))
    }
}
