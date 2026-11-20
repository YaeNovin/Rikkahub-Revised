package me.rerere.rikkahub

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.github.GitHubTokenStore
import org.junit.Assert.*
import org.junit.Test

/** Synthetic token, dedicated alias/file, and the isolated debug app's UID. */
class GitHubTokenStoreTest {
    @Test(timeout = 30_000) fun encryptedCredentialSurvivesReloadAndFailsClosedWhenKeyIsMissing() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "github-test-${java.util.UUID.randomUUID()}"
        val alias = "rikkahub.$name.v1"
        val file = File(context.noBackupFilesDir, "$name.enc")
        val store = GitHubTokenStore(context, name)
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            val synthetic = "synthetic-only-device-credential"
            store.save(synthetic)
            assertTrue(store.state.value.configured)
            assertTrue(file.exists())
            assertFalse(file.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
            assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(synthetic))
            assertNull(keystore.getKey(alias, null).encoded)
            val reloaded = GitHubTokenStore(context, name)
            assertEquals(synthetic, reloaded.read().token)
            assertFalse(reloaded.read().toString().contains(synthetic))
            keystore.deleteEntry(alias)
            val missing = GitHubTokenStore(context, name)
            assertNull(missing.read().token)
            assertTrue(missing.state.value.configured)
            assertTrue(missing.state.value.unreadable)
            missing.clear()
            assertFalse(file.exists())
            assertFalse(missing.state.value.configured)
        } finally {
            store.clear()
            if (keystore.containsAlias(alias)) keystore.deleteEntry(alias)
        }
    }
}
