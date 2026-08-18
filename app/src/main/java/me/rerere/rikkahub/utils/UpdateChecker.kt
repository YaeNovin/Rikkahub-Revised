package me.rerere.rikkahub.utils

import android.app.DownloadManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.common.http.await
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale

private const val API_URL =
    "https://api.github.com/repos/YaeNovin/Rikkahub-Revised/releases/latest"
private const val DOWNLOAD_URL_PREFIX =
    "https://github.com/YaeNovin/Rikkahub-Revised/releases/download/"
private const val UPDATE_CACHE_MILLIS = 30 * 60 * 1_000L
private val KNOWN_ANDROID_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

class UpdateChecker(private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val checkMutex = Mutex()
    private var cachedUpdate: CachedUpdate? = null

    fun checkUpdate(): Flow<UiState<UpdateInfo?>> = flow {
        emit(UiState.Loading)
        val updateInfo = checkMutex.withLock {
            val now = System.currentTimeMillis()
            val cached = cachedUpdate
            if (cached != null && now - cached.checkedAtMillis < UPDATE_CACHE_MILLIS) {
                cached.info
            } else {
                val response = client.newCall(
                    Request.Builder()
                        .url(API_URL)
                        .get()
                        .addHeader("Accept", "application/vnd.github+json")
                        .addHeader("X-GitHub-Api-Version", "2022-11-28")
                        .addHeader(
                            "User-Agent",
                            "Rikkahub-Revised ${BuildConfig.VERSION_NAME} #${BuildConfig.VERSION_CODE}",
                        )
                        .build()
                ).await()

                val fetched = response.use {
                    when {
                        response.code == 404 -> null
                        response.isSuccessful -> {
                            val release =
                                json.decodeFromString<GitHubRelease>(response.body.string())
                            release.toUpdateInfo(Build.SUPPORTED_ABIS.toList())
                        }
                        else -> throw IOException(
                            "GitHub release request failed (${response.code})"
                        )
                    }
                }
                cachedUpdate = CachedUpdate(now, fetched)
                fetched
            }
        }
        emit(UiState.Success(updateInfo))
    }.catch {
        emit(UiState.Error(it))
    }.flowOn(Dispatchers.IO)

    fun downloadUpdate(context: Context, download: UpdateDownload) {
        runCatching {
            val request = DownloadManager.Request(download.url.toUri()).apply {
                setTitle(download.name)
                setDescription(context.getString(R.string.update_card_downloading))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedNetworkTypes(
                    DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
                )
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, download.name)
                setMimeType("application/vnd.android.package-archive")
            }
            val downloadManager =
                context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)
        }.onFailure {
            Toast.makeText(
                context,
                context.getString(R.string.update_card_download_failed),
                Toast.LENGTH_SHORT,
            ).show()
            context.openUrl(download.url)
        }
    }
}

private data class CachedUpdate(
    val checkedAtMillis: Long,
    val info: UpdateInfo?,
)

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val body: String? = null,
    val assets: List<GitHubReleaseAsset> = emptyList(),
)

@Serializable
internal data class GitHubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long,
)

private fun GitHubRelease.toUpdateInfo(supportedAbis: List<String>) = UpdateInfo(
    version = tagName.trim(),
    publishedAt = publishedAt ?: createdAt.orEmpty(),
    changelog = body.orEmpty(),
    downloads = selectCompatibleApkDownloads(assets, supportedAbis),
)

internal fun selectCompatibleApkDownloads(
    assets: List<GitHubReleaseAsset>,
    supportedAbis: List<String>,
): List<UpdateDownload> {
    val safeApks = assets.filter { asset ->
        asset.name.endsWith(".apk", ignoreCase = true) &&
            asset.browserDownloadUrl.startsWith(DOWNLOAD_URL_PREFIX)
    }
    val preferredAbi = supportedAbis.firstNotNullOfOrNull { supportedAbi ->
        supportedAbi.lowercase(Locale.US).takeIf { abi ->
            safeApks.any { detectAssetAbi(it.name) == abi }
        }
    }

    return safeApks
        .filter { asset ->
            val assetAbi = detectAssetAbi(asset.name)
            asset.name.contains("universal", ignoreCase = true) ||
                (preferredAbi != null && assetAbi == preferredAbi)
        }
        .sortedBy { asset ->
            if (detectAssetAbi(asset.name) == preferredAbi) 0 else 1
        }
        .map { asset ->
            UpdateDownload(
                name = asset.name,
                url = asset.browserDownloadUrl,
                size = formatFileSize(asset.size),
            )
        }
}

private fun detectAssetAbi(name: String): String? {
    val normalizedName = name.lowercase(Locale.US)
    return KNOWN_ANDROID_ABIS.firstOrNull(normalizedName::contains)
}

internal fun formatFileSize(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_024 * 1_024 -> String.format(Locale.US, "%.1f KB", bytes / 1_024.0)
    bytes < 1_024L * 1_024 * 1_024 ->
        String.format(Locale.US, "%.1f MB", bytes / (1_024.0 * 1_024))
    else -> String.format(Locale.US, "%.1f GB", bytes / (1_024.0 * 1_024 * 1_024))
}

@Serializable
data class UpdateDownload(
    val name: String,
    val url: String,
    val size: String,
)

@Serializable
data class UpdateInfo(
    val version: String,
    val publishedAt: String,
    val changelog: String,
    val downloads: List<UpdateDownload>,
)

@JvmInline
value class Version(val value: String) : Comparable<Version> {
    private fun parse(): ParsedVersion {
        val normalized = value.trim().removeVersionPrefix().substringBefore('+')
        val match = VERSION_PATTERN.matchEntire(normalized)
        if (match == null) {
            return ParsedVersion(listOf(0), listOf(normalized.lowercase(Locale.US)))
        }

        val core = match.groupValues[1].split('.').map { it.toIntOrNull() ?: 0 }
        val suffix = match.groupValues[2]
            .trimStart('-', '.', '_')
            .takeIf(String::isNotBlank)
            ?.split(PRERELEASE_SEPARATOR)
            ?.filter(String::isNotBlank)
            ?.map { it.lowercase(Locale.US) }
        return ParsedVersion(core, suffix)
    }

    override fun compareTo(other: Version): Int {
        val left = parse()
        val right = other.parse()
        val maxCoreSize = maxOf(left.core.size, right.core.size)
        for (index in 0 until maxCoreSize) {
            val leftPart = left.core.getOrElse(index) { 0 }
            val rightPart = right.core.getOrElse(index) { 0 }
            if (leftPart != rightPart) return leftPart.compareTo(rightPart)
        }

        return when {
            left.prerelease == null && right.prerelease == null -> 0
            left.prerelease != null && right.prerelease == null -> -1
            left.prerelease == null && right.prerelease != null -> 1
            else -> comparePrerelease(left.prerelease!!, right.prerelease!!)
        }
    }

    companion object {
        fun compare(version1: String, version2: String): Int =
            Version(version1).compareTo(Version(version2))

        private fun comparePrerelease(left: List<String>, right: List<String>): Int {
            val maxSize = maxOf(left.size, right.size)
            for (index in 0 until maxSize) {
                if (index >= left.size) return -1
                if (index >= right.size) return 1

                val leftNumber = left[index].toIntOrNull()
                val rightNumber = right[index].toIntOrNull()
                val comparison = when {
                    leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                    leftNumber != null -> -1
                    rightNumber != null -> 1
                    else -> left[index].compareTo(right[index])
                }
                if (comparison != 0) return comparison
            }
            return 0
        }
    }
}

private fun String.removeVersionPrefix(): String =
    if (length > 1 && (first() == 'v' || first() == 'V') && this[1].isDigit()) substring(1) else this

private val VERSION_PATTERN = Regex("^(\\d+(?:\\.\\d+)*)(.*)$")
private val PRERELEASE_SEPARATOR = Regex("[-._]+")

private data class ParsedVersion(
    val core: List<Int>,
    val prerelease: List<String>?,
)

operator fun String.compareTo(other: Version): Int = Version(this).compareTo(other)
operator fun Version.compareTo(other: String): Int = compareTo(Version(other))
