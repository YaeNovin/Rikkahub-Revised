package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/** Only tiny sample/seed results are stored; no bitmaps and no network cache assumptions. */
internal class BackgroundAnalysisDiskCache(private val directory: File) {
    private fun file(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$digest.palette")
    }

    fun read(key: String): BackgroundBitmapAnalysis? = runCatching {
        val file = file(key)
        if (!file.isFile || file.length() > 4096) return null
        DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readInt() != 3) return null
            val seed = if (input.readBoolean()) input.readInt() else null
            val count = input.readInt()
            if (count !in 1..512) return null
            val samples = List(count) { Color(input.readInt()) }
            if (input.read() != -1) return null
            file.setLastModified(System.currentTimeMillis())
            BackgroundBitmapAnalysis(samples, seed)
        }
    }.getOrNull()

    fun write(key: String, analysis: BackgroundBitmapAnalysis) {
        if (analysis.rawSamples.isEmpty() || analysis.rawSamples.size > 512) return
        runCatching {
            directory.mkdirs()
            val file = file(key)
            val temporary = File(directory, "${file.name}.tmp")
            try {
                DataOutputStream(temporary.outputStream().buffered()).use { output ->
                    output.writeInt(3)
                    output.writeBoolean(analysis.seedArgb != null)
                    analysis.seedArgb?.let(output::writeInt)
                    output.writeInt(analysis.rawSamples.size)
                    analysis.rawSamples.forEach { output.writeInt(it.toArgb()) }
                }
                if (!temporary.renameTo(file)) temporary.delete()
                directory.listFiles { candidate -> candidate.extension == "palette" }.orEmpty()
                    .sortedByDescending { it.lastModified() }.drop(8).forEach { it.delete() }
            } finally { temporary.delete() }
        }
    }
}
