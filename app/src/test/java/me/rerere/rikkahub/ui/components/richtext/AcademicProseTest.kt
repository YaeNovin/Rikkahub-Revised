package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import me.rerere.rikkahub.data.render.LinguisticRenderingSettings
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AcademicProseTest {
    private fun rendered(source: String, mode: MarkdownRenderMode = MarkdownRenderMode.STANDARD,
        settings: LinguisticRenderingSettings = LinguisticRenderingSettings(), math: Boolean = true): String =
        academicProseHtml(buildMarkdownDocument(source, mode).body().childNodes(), settings, math)

    @Test fun rubyAndIpaBelongToTheSameParagraphForBothMessageRoles() {
        val source = "Read <ruby>漢字<rt>hàn zì</rt></ruby> and /ˈkæt/ or <ipa>kat</ipa>."
        MarkdownRenderMode.entries.forEach { mode ->
            val doc = Jsoup.parseBodyFragment(rendered(source, mode))
            assertEquals("hàn zì", doc.selectFirst("ruby rt")!!.text())
            assertEquals(listOf("/ˈkæt/", "/kat/"), doc.select(".ipa").map { it.text() })
            assertEquals(1, doc.select(".prose-paragraph").size)
        }
    }

    @Test fun codeDestinationsEscapesAndUnfinishedMarkupRemainLiteral() {
        for (source in listOf("`<ruby>漢<rt>かん</rt></ruby> /ɐ/ ${'$'}x^2${'$'}`",
            "```text\n<ruby>漢<rt>かん</rt></ruby>\n\\ce{H2O}\n```",
            "    <ruby>漢<rt>かん</rt></ruby>", "<code>/ɐ/ ${'$'}x^2${'$'}</code>",
            "[path](https://example.org/ɐ/)", "\\/ɐ/", "<ruby>漢<rt>かん")) {
            val doc = buildMarkdownDocument(source)
            assertTrue(source, doc.select("[$ACADEMIC_SOURCE]").isEmpty())
        }
        assertEquals("/tmp/ /etc/ https://example.org/abc/", decorateInlineIpa("/tmp/ /etc/ https://example.org/abc/"))
    }

    @Test fun latexAndChemistrySurviveTightDelimitersMarkdownAndHtmlRoutes() {
        val source = "紧邻${'$'}x_i^2+\\frac{a}{b}${'$'}中文；\\(\\ce{2H2 + O2 -> 2H2O}\\)；\\ce{Fe^{3+}}\n\n\\[\\begin{aligned}x&=1\\\\y&=2\\end{aligned}\\]"
        MarkdownRenderMode.entries.forEach { mode ->
            val doc = Jsoup.parseBodyFragment(rendered(source, mode))
            val formulas = doc.select(".prose-math")
            assertEquals(4, formulas.size)
            assertEquals("x_i^2+\\frac{a}{b}", formulas[0].wholeText())
            assertEquals("\\ce{2H2 + O2 -> 2H2O}", formulas[1].wholeText())
            assertEquals("\\ce{Fe^{3+}}", formulas[2].wholeText())
            assertTrue(formulas[3].hasClass("display-math"))
        }
        assertEquals("price ${'$'}5 and ${'$'}10", protectMarkdownMath("price ${'$'}5 and ${'$'}10"))
        assertEquals("incomplete ${'$'}x", protectMarkdownMath("incomplete ${'$'}x"))
        assertEquals("$$\\begin{align}x&=1\\end{align}$$", normalizeMarkdownLatex("$$\\begin{align}x&=1\\end{align}$$"))
    }

    @Test fun apparatusKeepsLemmaWitnessAndEditorialSemanticsWithoutRequests() {
        val doc = Jsoup.parseBodyFragment(rendered("本文<app><lem>春</lem><rdg wit=\"#A #B\">花</rdg><note n=\"1\">底本</note></app>" +
            "<choice><sic>旧</sic><corr>舊</corr></choice><note>说明</note>"))
        assertTrue(doc.selectFirst("details summary")!!.text().startsWith("春"))
        assertTrue(doc.text().contains("A B：花"))
        assertTrue(doc.text().contains("sic：旧"))
        assertTrue(doc.text().contains("corr：舊"))
        assertTrue(doc.text().contains("说明"))
    }

    @Test fun verticalPreservesHistoricalKanaAndOnlyControlledLayout() {
        val source = "<vertical lang=\"ja\" style=\"background:url(https://bad.test/x)\"><ruby>ゐ<rt>い</rt></ruby>ゑ・ヰヱ〱〲々ゝゞ。「古語」<tcy>12</tcy><unclear>疑</unclear><supplied>補</supplied><gap extent=\"二字\"/></vertical>"
        val doc = Jsoup.parseBodyFragment(rendered(source))
        assertNotNull(doc.selectFirst(".vertical-rl[lang=ja]"))
        assertTrue(doc.text().contains("ゑ・ヰヱ〱〲々ゝゞ"))
        assertEquals("12", doc.selectFirst(".tcy")!!.text())
        assertTrue(doc.select("[style],img,script").isEmpty())
        assertFalse(doc.html().contains("https://bad.test"))
    }

    @Test fun disabledSettingsKeepReadableSourceAndNeverHideTheBaseText() {
        val disabled = LinguisticRenderingSettings(ipa = false, ruby = false, syntaxTree = false, literary = false)
        val doc = Jsoup.parseBodyFragment(rendered("<ruby>漢<rt>かん</rt></ruby><ipa>kɑː</ipa><vertical>古文</vertical><app><lem>春</lem><rdg>花</rdg></app>", settings = disabled))
        assertTrue(doc.select("ruby,.ipa,.vertical-rl,details").isEmpty())
        assertTrue(doc.text().contains("漢")); assertTrue(doc.text().contains("かん")); assertTrue(doc.text().contains("古文"))
        assertTrue(Jsoup.parseBodyFragment(rendered("${'$'}x^2${'$'}", math = false)).text().contains("x^2"))
    }

    @Test fun encodedCjkFragmentsUseTheUtf8BudgetWithoutDroppingTheirContents() {
        val text = "漢".repeat(18000)
        val marker = academicSourceMarker("<vertical>$text</vertical>", true)
        val element = Jsoup.parseBodyFragment(marker).selectFirst("[$ACADEMIC_SOURCE]")!!
        assertEquals("<vertical>$text</vertical>", academicSource(element))
        assertEquals(18000, Jsoup.parseBodyFragment(academicProseHtml(listOf(element), LinguisticRenderingSettings(), true)).text().count { it == '漢' })
    }

    @Test fun proseDecorationsRemainAvailableBesideAnnotationsButDoNotModifyLiteralIslands() {
        val nodes = buildMarkdownDocument("<ruby>漢<rt>かん</rt></ruby> “鲜艳文字” #12ABEF `\"code\" #112233`").body().childNodes()
        val doc = Jsoup.parseBodyFragment(academicProseHtml(nodes, LinguisticRenderingSettings(), true,
            ChatInlineOptions(hexSwatches = true, quotedText = true)))
        assertEquals("鲜艳文字", doc.selectFirst(".quote-accent")!!.text())
        assertEquals(1, doc.select(".hex-swatch").size)
        assertEquals("background:#12ABEF", doc.selectFirst(".hex-swatch")!!.attr("style"))
        assertTrue(doc.select("code .quote-accent,code .hex-swatch,rt .quote-accent").isEmpty())
    }

    @Test fun unfencedTreeRequiresExplicitWrapperAndTheExistingBoundedSchema() {
        val tree = """{"version":1,"root":{"label":"S","children":[{"label":"NP"},{"label":"VP"}]}}"""
        assertEquals(1, Jsoup.parseBodyFragment(rendered("<syntax-tree>$tree</syntax-tree>")).select(".syntax-tree[data-spec]").size)
        assertTrue(buildMarkdownDocument(tree).select("[$ACADEMIC_SOURCE]").isEmpty())
        assertFalse(rendered("<syntax-tree>function(){}</syntax-tree>").contains("data-spec"))
    }

    @Test fun wholeMessagePreviewUsesTheSameSanitizedNotationWithoutEnablingAuthorHtml() {
        val source = "<ruby>漢字<rt>かんじ</rt></ruby> /t͡ʃ/ \\(x^2\\) \\ce{H2O}\n\n<vertical lang=\"ja\">古文。</vertical>\n\n`\\ce{literal}`"
        val template = File("src/main/assets/html/mark.html").readText()
        val html = buildMarkdownPreviewHtml(template, source, darkColorScheme())
        assertFalse(html.contains("{{ACADEMIC_FRAGMENTS_JSON}}"))
        assertTrue(html.contains("html: false"))
        val dir = File("build/reports/academic-prose").apply { mkdirs() }
        dir.resolve("preview-dark.html").writeText(html)
        assertEquals(source, Jsoup.parse(html).getElementById("preview-fallback")!!.wholeText())
    }

    @Test fun generatedPagesForOfflineBrowserAndMobileChecks() {
        val fixtures = linkedMapOf(
            "inline" to "这是<ruby>汉字<rt>hàn zì</rt></ruby>、<ruby lang=\"zh-Bopo\" data-position=\"inter-character\">漢<rt>ㄏㄢˋ</rt></ruby>，读作 /ˈlɪŋ.ɡwɪs.tɪks/。英文 <ipa>kat</ipa>。",
            "vertical" to "<vertical lang=\"ja\"><ruby>古語<rt>こご</rt></ruby>。「ゐゑヰヱ」々ゝゞ〱〲、<tcy>12</tcy>。" + "<ruby>物語<rt>ものがたり</rt></ruby>の文。".repeat(38) + "</vertical>",
            "apparatus" to "本文<app><lem>春</lem><rdg wit=\"#A\">花</rdg><rdg wit=\"#B\">芳</rdg><note>底本は春。</note></app>、<choice><sic>旧</sic><corr>舊</corr></choice><note n=\"一\">校注の詳細</note>。",
            "math" to "紧邻${'$'}x_i^2+\\frac{1}{2}${'$'}中文。\\(\\ce{2H2 + O2 -> 2H2O}\\)；\\pu{123 kJ mol-1}。\n\n\\[\\begin{align}x&=1\\\\y&=2\\end{align}\\]",
            "tree" to "<syntax-tree>{\"version\":1,\"root\":{\"label\":\"S\",\"children\":[{\"label\":\"NP\"},{\"label\":\"VP\"}]}}</syntax-tree>",
            "unsafe" to "<ruby onclick=\"fetch('https://bad.test')\">漢<rt><img src=\"https://bad.test/a\" onerror=\"alert(1)\">かん</rt></ruby><ipa>ɐ</ipa>",
            "long" to ("这是<ruby>漢字<rt>かんじ</rt></ruby>和 /t͡ʃ/，${'$'}x^2${'$'}。".repeat(160)),
        )
        val directory = File("build/reports/academic-prose").apply { mkdirs() }
        fixtures.forEach { (name, source) ->
            listOf("light" to lightColorScheme(), "dark" to darkColorScheme()).forEach { (theme, colors) ->
                val nodes = buildMarkdownDocument(source).body().childNodes()
                directory.resolve("$name-$theme.html").writeText(buildAcademicProsePage(nodes, LinguisticRenderingSettings(), true, colors))
                if (name == "inline") directory.resolve("$name-$theme-disabled.html").writeText(buildAcademicProsePage(nodes,
                    LinguisticRenderingSettings(ipa = false, ruby = false, literary = false), false, colors))
            }
        }
    }
}
