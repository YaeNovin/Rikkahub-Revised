package me.rerere.asr

import org.junit.Assert.*
import org.junit.Test

class TranscriptAccumulatorTest {
    @Test fun finalEventsCanArriveOutOfOrderAndRepeat() {
        val result = TranscriptAccumulator()
        result.register("b", "a")
        result.update("b", "第二句", final = true)
        result.update("a", "第一句", final = true)
        result.update("b", "第二句", final = true)
        assertEquals("第一句 第二句", result.text())
        assertFalse(result.hasPending())
    }
    @Test fun finalResultReplacesDraftAndIgnoresLateDeltas() {
        val result = TranscriptAccumulator()
        result.update("a", "背景")
        result.update("a", "北京", final = true)
        result.update("a", "市", append = true)
        assertEquals("北京", result.text())
    }
    @Test fun cyclesCannotHangAndClearSeparatesSessions() {
        val result = TranscriptAccumulator()
        result.register("a", "b"); result.register("b", "a")
        result.update("a", "A"); result.update("b", "B")
        assertEquals(2, result.text().split(" ").size)
        result.clear()
        assertEquals("", result.text())
        assertFalse(result.hasPending())
    }
}
