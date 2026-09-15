package me.rerere.tts.controller

import org.junit.Assert.*
import org.junit.Test

class TextChunkerTest {
    @Test fun longTextWithoutPunctuationIsBoundedAndLossless() {
        val input = "字".repeat(1501)
        val chunks = TextChunker(160).split(input)
        assertTrue(chunks.all { it.text.length <= 160 })
        assertEquals(input, chunks.joinToString("") { it.text })
    }
    @Test fun cannotSplitUnicodeSurrogatePairs() {
        val input = "😀".repeat(100)
        val chunks = TextChunker(9).split(input)
        assertEquals(input, chunks.joinToString("") { it.text })
        assertTrue(chunks.none { it.text.first().isLowSurrogate() || it.text.last().isHighSurrogate() })
    }
    @Test fun pauseAndEmotionTagsStayIntact() {
        val chunks = TextChunker(12).split("这是长段内容的开头[深呼吸]继续朗读<#1.25#>稍作停顿")
        assertTrue(chunks.any { "[深呼吸]" in it.text })
        assertTrue(chunks.any { "<#1.25#>" in it.text })
    }
}
