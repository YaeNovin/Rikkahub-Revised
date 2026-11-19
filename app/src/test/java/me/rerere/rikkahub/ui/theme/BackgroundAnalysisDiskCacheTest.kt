package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackgroundAnalysisDiskCacheTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `palette survives process memory loss and source revisions remain isolated`() {
        val directory = folder.newFolder()
        val analysis = BackgroundBitmapAnalysis(listOf(Color.Red, Color.Blue, Color.Transparent), 0xffaa3366.toInt())
        BackgroundAnalysisDiskCache(directory).write("photo:mtime1:size1:aspect450", analysis)
        val restarted = BackgroundAnalysisDiskCache(directory)
        assertEquals(analysis, restarted.read("photo:mtime1:size1:aspect450"))
        assertNull(restarted.read("photo:mtime2:size1:aspect450"))
        assertNull(restarted.read("photo:mtime1:size1:aspect600"))
    }

    @Test fun `corrupt and interrupted caches cannot break startup and retention is bounded`() {
        val directory = folder.newFolder()
        val cache = BackgroundAnalysisDiskCache(directory)
        val analysis = BackgroundBitmapAnalysis(listOf(Color.Green), null)
        repeat(12) { cache.write("image$it", analysis) }
        assertTrue(directory.listFiles()!!.size <= 8)
        val corruptDirectory = folder.newFolder()
        val corruptCache = BackgroundAnalysisDiskCache(corruptDirectory)
        corruptCache.write("latest", analysis)
        val file = corruptDirectory.listFiles()!!.single()
        file.writeBytes(byteArrayOf(0, 0, 0, 3, 1))
        assertNull(corruptCache.read("latest"))
    }
}
