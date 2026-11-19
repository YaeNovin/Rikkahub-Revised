import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class WorkspaceTermuxAarTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun fixture(): File = temporary.newFile("terminal-emulator.aar").also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            mapOf(
                "classes.jar" to byteArrayOf(1, 2, 3, 4),
                "AndroidManifest.xml" to "<manifest package=\"com.termux\"/>".toByteArray(),
                "META-INF/LICENSE" to "upstream license".toByteArray(),
                "jni/arm64-v8a/libtermux.so" to byteArrayOf(5),
                "jni/x86_64/libtermux.so" to byteArrayOf(6),
                "jni/arm64-v8a/libanother.so" to byteArrayOf(7),
                "assets/libtermux.so" to byteArrayOf(8),
            ).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
    }

    @Test fun `remove only the upstream termux JNI across ABIs and retain API resources and licenses`() {
        val input = fixture()
        val output = temporary.newFile("filtered.aar")
        stripUpstreamTermuxJni(input, output)
        ZipFile(input).use { source -> ZipFile(output).use { filtered ->
            assertNull(filtered.getEntry("jni/arm64-v8a/libtermux.so"))
            assertNull(filtered.getEntry("jni/x86_64/libtermux.so"))
            assertEquals(5, filtered.size())
            filtered.entries().asSequence().forEach { entry ->
                assertArrayEquals(source.getInputStream(source.getEntry(entry.name)).use { it.readBytes() },
                    filtered.getInputStream(entry).use { it.readBytes() })
            }
            assertNotNull(source.getEntry("jni/arm64-v8a/libtermux.so"))
        } }
    }

    @Test fun `transform output is deterministic`() {
        val input = fixture()
        val first = temporary.newFile("first.aar")
        val second = temporary.newFile("second.aar")
        stripUpstreamTermuxJni(input, first)
        stripUpstreamTermuxJni(input, second)
        assertArrayEquals(first.readBytes(), second.readBytes())
    }
}
