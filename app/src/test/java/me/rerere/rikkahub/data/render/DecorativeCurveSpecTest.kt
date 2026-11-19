package me.rerere.rikkahub.data.render

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DecorativeCurveSpecTest {
    private val renderers = listOf("sigil", "pattern")
    private fun compile(id: String, layer: String): JsonObject =
        compileDeclarativeRenderSpec(id, """{"layers":[$layer]}""")
            .getValue("layers").jsonArray.single().jsonObject

    @Test fun `all formulas share canonical geometry styling and safe defaults in both backends`() {
        for (formula in listOf("hypotrochoid", "epitrochoid", "lissajous", "harmonic")) {
            val source = """{"type":"curve","formula":"$formula","color":"#22d3ee","fill":"#102030","background":"#07111f","symmetry":2,"mirror":true,"animation":{"mode":"draw"}}"""
            val sigil = compile("sigil", source)
            assertEquals(sigil, compile("pattern", source))
            assertEquals(formula, sigil.getValue("curve").jsonObject.getValue("formula").jsonPrimitive.content)
            assertEquals(1, sigil.getValue("curve").jsonObject.getValue("version").jsonPrimitive.int)
            assertEquals("#102030", sigil.getValue("fill").jsonPrimitive.content)
            assertEquals("draw", sigil.getValue("animation").jsonObject.getValue("mode").jsonPrimitive.content)
        }
        assertEquals("hypotrochoid", compile("sigil", """{"type":"curve"}""")
            .getValue("curve").jsonObject.getValue("formula").jsonPrimitive.content)
    }

    @Test fun `ratios normalize to a finite common period and sampling remains app owned`() {
        val inner = compile("sigil", """{"type":"curve","fixed":21,"rolling":9,"pen":0.8}""").getValue("curve").jsonObject
        assertEquals(7, inner.getValue("fixed").jsonPrimitive.int)
        assertEquals(3, inner.getValue("rolling").jsonPrimitive.int)
        assertEquals(384, inner.getValue("samples").jsonPrimitive.int)
        assertEquals(0.8, inner.getValue("pen").jsonPrimitive.double, 0.0)
        val lis = compile("pattern", """{"type":"curve","formula":"lissajous","frequencyX":6,"frequencyY":4,"phase":23}""").getValue("curve").jsonObject
        assertEquals(3, lis.getValue("frequencyX").jsonPrimitive.int)
        assertEquals(2, lis.getValue("frequencyY").jsonPrimitive.int)
        assertEquals(23.0, lis.getValue("phase").jsonPrimitive.double, 0.0)
        assertEquals(288, lis.getValue("samples").jsonPrimitive.int)
    }

    @Test fun `harmonics preserve signed amplitudes and per term degree phase`() {
        val curve = compile("sigil", """{"type":"curve","formula":"harmonic","terms":[{"frequency":5,"amplitude":-0.4,"phase":45},{"frequency":16,"amplitude":0.5}]}""")
            .getValue("curve").jsonObject
        assertEquals(1632, curve.getValue("samples").jsonPrimitive.int)
        val terms = curve.getValue("terms").jsonArray
        assertEquals(-0.4, terms.first().jsonObject.getValue("amplitude").jsonPrimitive.double, 0.0)
        assertEquals(45.0, terms.first().jsonObject.getValue("phase").jsonPrimitive.double, 0.0)
        assertEquals(0.0, terms.last().jsonObject.getValue("phase").jsonPrimitive.double, 0.0)
    }

    @Test fun `reject unsupported formulas scripts sampling overrides and wrong family fields`() {
        val invalid = listOf(
            """{"type":"curve","formula":"sin(t)"}""",
            """{"type":"curve","formula":"window.alert(1)"}""",
            """{"type":"curve","samples":999999}""",
            """{"type":"curve","expression":"cos(t)"}""",
            """{"type":"curve","curve":{"version":1}}""",
            """{"type":"curve","formula":"lissajous","fixed":3}""",
            """{"type":"curve","formula":"harmonic","phase":20}""",
            """{"type":"curve","phase":20}""",
            """{"type":"curve","formula":"harmonic","terms":[{"frequency":2,"script":"alert(1)"}]}""",
        )
        for (id in renderers) invalid.forEach { assertTrue("$id: $it", runCatching { compile(id, it) }.isFailure) }
    }

    @Test fun `reject singular unbounded and incorrectly typed parameters`() {
        val invalid = listOf(
            """{"type":"curve","fixed":3,"rolling":3}""",
            """{"type":"curve","fixed":2,"rolling":3}""",
            """{"type":"curve","rolling":0}""",
            """{"type":"curve","fixed":25}""",
            """{"type":"curve","fixed":3.5}""",
            """{"type":"curve","fixed":"7"}""",
            """{"type":"curve","pen":1e999}""",
            """{"type":"curve","pen":-0.1}""",
            """{"type":"curve","radius":0}""",
            """{"type":"curve","formula":"lissajous","frequencyY":0}""",
            """{"type":"curve","formula":"lissajous","phase":361}""",
            """{"type":"curve","formula":"harmonic","terms":[]}""",
            """{"type":"curve","formula":"harmonic","terms":[{"amplitude":0.8},{"amplitude":0.11}]}""",
            """{"type":"curve","formula":"harmonic","terms":[{"frequency":17}]}""",
            """{"type":"curve","formula":"harmonic","terms":[{"amplitude":"0.2"}]}""",
        )
        for (id in renderers) invalid.forEach { assertTrue("$id: $it", runCatching { compile(id, it) }.isFailure) }
        for (id in renderers) assertTrue(runCatching {
            compile(id, """{"type":"curve","formula":"harmonic","terms":[${List(7) { "{}" }.joinToString()}]}""")
        }.isFailure)
    }

    @Test fun `budget includes all curve layers and displayed symmetry copies`() {
        for (id in renderers) {
            val source = """{"type":"curve","formula":"epitrochoid","fixed":24,"rolling":11,"symmetry":7}"""
            assertEquals(3360, compile(id, source).getValue("curve").jsonObject.getValue("samples").jsonPrimitive.int)
            assertTrue(runCatching { compile(id, source.replace("\"symmetry\":7", "\"symmetry\":8")) }.isFailure)
            val layer = source.replace("\"symmetry\":7", "\"symmetry\":4")
            assertTrue(runCatching { compileDeclarativeRenderSpec(id, """{"layers":[$layer,$layer]}""") }.isFailure)
        }
    }

    @Test fun `pattern total base vertex budget includes new sampled curves with old geometry`() {
        val fractal = """{"type":"fractal","variant":"sierpinski","depth":7}"""
        val curve = """{"type":"curve","formula":"epitrochoid","fixed":24,"rolling":11}"""
        assertTrue(runCatching { compileDeclarativeRenderSpec("pattern", """{"layers":[$fractal,$fractal,$fractal,$curve]}""") }.isSuccess)
        assertTrue(runCatching { compileDeclarativeRenderSpec("pattern", """{"layers":[$fractal,$fractal,$fractal,$curve,$curve]}""") }.isFailure)
    }
}
