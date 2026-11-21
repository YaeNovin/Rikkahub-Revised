package me.rerere.rikkahub.data.render

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ScoreDocumentsTest {
    private fun xml(parts: Int = 1, measures: Int = 1) = "<score-partwise version='4.0'><part-list>" +
        (1..parts).joinToString("") { "<score-part id='P$it'><part-name>Piano</part-name></score-part>" } + "</part-list>" +
        (1..parts).joinToString("") { p -> "<part id='P$p'>" + (1..measures).joinToString("") { "<measure number='$it'><note><rest/><duration>4</duration><type>whole</type></note></measure>" } + "</part>" } + "</score-partwise>"
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> entries.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() } }
    }.toByteArray()
    private val container = """<container><rootfiles><rootfile full-path="scores/main.musicxml" media-type="application/vnd.recordare.musicxml+xml"/></rootfiles></container>""".toByteArray()

    @Test fun `multipart scores count measures per part rather than summing parts`() {
        assertEquals("musicxml", ScoreDocuments.parseXml(xml(2, 250), "musicxml").renderer)
        assertThrows(IllegalArgumentException::class.java) { ScoreDocuments.parseXml(xml(1, 513), "musicxml") }
    }
    @Test fun `external MusicXML DTD is stripped and never resolved`() {
        val source = """<!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 4.0 Partwise//EN" "https://invalid.example/music.dtd">""" + xml()
        assertFalse(ScoreDocuments.parseXml(source, "musicxml").source.contains("DOCTYPE"))
    }
    @Test fun `entities scripts resources and unsupported score roots fail closed`() {
        for(source in listOf("<!DOCTYPE score-partwise [<!ENTITY x SYSTEM 'file:///private'>]>"+xml(),
            xml().replace("<note>","<image source='https://example.com/a.png'/><note>"),
            xml().replace("<note>","<direction onclick='alert(1)'/><note>"),
            xml().replace("score-partwise","score-timewise"))) {
            assertThrows(IllegalArgumentException::class.java) { ScoreDocuments.parseXml(source,"musicxml") }
        }
    }
    @Test fun `MEI preserves genuine mensural and neume encodings`() {
        for(source in listOf(MEI_MENSURAL_EXAMPLE, MEI_NEUME_EXAMPLE)) {
            assertEquals(source, ScoreDocuments.parseXml(source,"mei").source)
            assertEquals("mei",ScoreDocuments.decode(source.toByteArray(),"score.xml")!!.renderer)
        }
        assertThrows(IllegalArgumentException::class.java) { ScoreDocuments.parseXml(MEI_NEUME_EXAMPLE.replace("<mdiv>","<mdiv/><mdiv>"),"mei") }
    }
    @Test fun `UTF16 MusicXML attachments normalize Unicode without corrupting text`() {
        val source="<?xml version='1.0' encoding='UTF-16'?>"+xml().replace("Piano","曲谱 🎼")
        val result=ScoreDocuments.decode(source.toByteArray(Charsets.UTF_16),"score.musicxml")!!
        assertTrue(result.source.contains("曲谱 🎼")); assertTrue(result.source.contains("encoding=\"UTF-8\""))
    }
    @Test fun `MXL resolves declared main score and ignores alternate metadata`() {
        val result=ScoreDocuments.decode(zip("META-INF/container.xml" to container,"scores/main.musicxml" to xml().toByteArray(),"metadata.xml" to "unused".toByteArray()),"song.mxl")!!
        assertEquals("musicxml",result.renderer);assertEquals(xml(),result.source)
    }
    @Test fun `MXL rejects traversal missing entry oversized expansion and excessive entries`() {
        for(bytes in listOf(zip("../bad.xml" to xml().toByteArray()),zip("META-INF/container.xml" to container),
            zip("META-INF/container.xml" to container,"scores/main.musicxml" to ByteArray(2*1024*1024+1){' '.code.toByte()}),
            zip(*(1..65).map { "$it.xml" to "x".toByteArray() }.toTypedArray()))) {
            assertThrows(IllegalArgumentException::class.java) { ScoreDocuments.decode(bytes,"song.mxl") }
        }
    }
    @Test fun `ordinary XML remains a document and malformed explicit score is reported`() {
        assertNull(ScoreDocuments.decode("<settings><option>1</option></settings>".toByteArray(),"settings.xml"))
        assertThrows(IllegalArgumentException::class.java) { ScoreDocuments.decode("<settings/>".toByteArray(),"song.musicxml") }
        for(name in listOf("s.xml","s.mxl","s.mei","s.abc","s.jianpu"))assertTrue(ScoreDocuments.isCandidate(name))
    }
}
