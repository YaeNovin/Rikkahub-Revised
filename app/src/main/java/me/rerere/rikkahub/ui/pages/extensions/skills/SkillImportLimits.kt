package me.rerere.rikkahub.ui.pages.extensions.skills

import java.io.InputStream
import java.io.ByteArrayOutputStream

internal const val MAX_SKILL_FILE_BYTES = 8 * 1024 * 1024
internal const val MAX_SKILL_IMPORT_BYTES = 32 * 1024 * 1024
internal const val MAX_SKILL_IMPORT_FILES = 512

internal fun InputStream.readSkillBytes(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(count <= limit - output.size()) { "技能文件过大，单文件最多 8 MiB，导入总量最多 32 MiB" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
