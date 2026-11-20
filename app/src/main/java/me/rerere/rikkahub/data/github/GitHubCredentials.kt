package me.rerere.rikkahub.data.github

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class GitHubTokenState(val configured: Boolean = false, val unreadable: Boolean = false, val revision: Long = 0)

/** Deliberately not serializable; diagnostics must never interpolate a bearer value. */
class GitHubCredentials(val token: String? = null, val revision: Long = 0, val unreadable: Boolean = false) {
    val scope: String = token?.let {
        "token:" + MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }
    } ?: "public"
    override fun toString() = "GitHubCredentials(configured=${token != null}, unreadable=$unreadable)"
}

interface GitHubCredentialSource {
    val state: StateFlow<GitHubTokenState>
    suspend fun read(): GitHubCredentials
}

object AnonymousGitHubCredentials : GitHubCredentialSource {
    override val state = MutableStateFlow(GitHubTokenState())
    override suspend fun read() = GitHubCredentials()
}

/** AES-GCM envelope; the non-exportable key belongs to this Android application's UID. */
internal object GitHubTokenCipher {
    fun encrypt(value: String, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD("rikkahub.github.token.v1".toByteArray())
        return byteArrayOf(1, cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    }
    fun decrypt(envelope: ByteArray, key: SecretKey): String {
        require(envelope.size in 31..8192 && envelope[0] == 1.toByte() && envelope[1] == 12.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.copyOfRange(2, 14)))
        cipher.updateAAD("rikkahub.github.token.v1".toByteArray())
        return cipher.doFinal(envelope.copyOfRange(14, envelope.size)).toString(Charsets.UTF_8)
    }
}

class GitHubTokenStore(context: Context, storageName: String = "github-token") : GitHubCredentialSource {
    private val mutex = Mutex()
    // Outside files/, shared preferences, Settings, Room, backup ZIPs and Web DTOs.
    private val file = AtomicFile(File(context.noBackupFilesDir, "$storageName.enc"))
    private val alias = "rikkahub.$storageName.v1"
    private val mutableState = MutableStateFlow(GitHubTokenState(configured = file.baseFile.exists()))
    override val state: StateFlow<GitHubTokenState> = mutableState
    private var loaded: GitHubCredentials? = null

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "GitHub credential key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    override suspend fun read(): GitHubCredentials = withContext(Dispatchers.IO) {
        mutex.withLock {
            loaded ?: run {
                val revision = mutableState.value.revision
                val result = if (!file.baseFile.exists()) GitHubCredentials(revision = revision) else try {
                    check(file.baseFile.length() <= 8192)
                    GitHubCredentials(GitHubTokenCipher.decrypt(file.readFully(), key(false)), revision)
                } catch (_: Exception) {
                    GitHubCredentials(revision = revision, unreadable = true)
                }
                loaded = result
                mutableState.value = GitHubTokenState(result.token != null || result.unreadable, result.unreadable, revision)
                result
            }
        }
    }

    suspend fun save(value: String) = withContext(Dispatchers.IO) {
        val token = value.trim()
        require(token.length in 1..4096 && token.all { it.code in 33..126 }) { "Token 格式无效，请勿包含空格或换行" }
        mutex.withLock {
            val encrypted = try { GitHubTokenCipher.encrypt(token, key(true)) }
            catch (_: java.security.GeneralSecurityException) {
                // Explicit replacement can recover from an invalidated device key.
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
                GitHubTokenCipher.encrypt(token, key(true))
            }
            var stream: java.io.FileOutputStream? = null
            try {
                stream = file.startWrite()
                stream.write(encrypted)
                file.finishWrite(stream)
            } catch (_: Exception) {
                file.failWrite(stream)
                error("无法安全保存 GitHub Token")
            }
            val revision = mutableState.value.revision + 1
            loaded = GitHubCredentials(token, revision)
            mutableState.value = GitHubTokenState(configured = true, revision = revision)
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            file.delete()
            check(!file.baseFile.exists()) { "无法移除 GitHub Token" }
            runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
            val revision = mutableState.value.revision + 1
            loaded = GitHubCredentials(revision = revision)
            mutableState.value = GitHubTokenState(revision = revision)
        }
    }
}

data class GitHubRequestBudgets(val anonymous: Int = 50, val authenticated: Int = 4500) {
    fun limit(authenticatedRequest: Boolean) = if (authenticatedRequest) authenticated.coerceIn(1, 5000) else anonymous.coerceIn(1, 60)
}

data class GitHubApiQuota(val scope: String, val localUsed: Int, val localLimit: Int,
    val remaining: Long? = null, val serverLimit: Long? = null, val resetAt: Long? = null)

@kotlinx.serialization.Serializable
enum class GitHubCardStyle { NATIVE, CLASSIC }
