package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun `selects only the ABI used by the installed app`() {
        val downloads = selectCompatibleApkDownloads(
            assets = listOf(
                asset("app-x86_64-release.apk", 30L * 1_024 * 1_024),
                asset("app-armeabi-v7a-release.apk", 31L * 1_024 * 1_024),
                asset("app-arm64-v8a-release.apk", 32L * 1_024 * 1_024),
                asset("app-universal-release.apk", 60L * 1_024 * 1_024),
            ),
            installedAbi = "arm64-v8a",
        )

        assertEquals(
            listOf("app-arm64-v8a-release.apk"),
            downloads.map { it.name },
        )
        assertEquals("32.0 MB", downloads.first().size)
    }

    @Test
    fun `does not treat x86_64 asset as x86 compatible`() {
        val downloads = selectCompatibleApkDownloads(
            assets = listOf(
                asset("app-x86_64-release.apk"),
                asset("app-universal-release.apk"),
            ),
            installedAbi = "x86",
        )

        assertEquals(emptyList<UpdateDownload>(), downloads)
    }

    @Test
    fun `rejects non apk and foreign download urls`() {
        val downloads = selectCompatibleApkDownloads(
            assets = listOf(
                asset("checksums.txt"),
                GitHubReleaseAsset(
                    name = "app-arm64-v8a-release.apk",
                    browserDownloadUrl = "https://example.com/app-arm64-v8a-release.apk",
                    size = 1_024,
                ),
            ),
            installedAbi = "arm64-v8a",
        )

        assertEquals(emptyList<UpdateDownload>(), downloads)
    }

    @Test
    fun `does not offer an APK when the installed ABI is unknown`() {
        val downloads = selectCompatibleApkDownloads(
            assets = listOf(
                asset("app-arm64-v8a-release.apk"),
                asset("app-universal-release.apk"),
            ),
            installedAbi = null,
        )

        assertEquals(emptyList<UpdateDownload>(), downloads)
    }

    @Test
    fun `detects installed ABI from native library directory before APK fallback`() {
        assertEquals(
            "armeabi-v7a",
            detectInstalledAppAbi(
                nativeLibraryDir = "/data/app/example/lib/arm",
                packagedAbis = setOf("arm64-v8a", "armeabi-v7a"),
                supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            ),
        )
        assertEquals(
            "arm64-v8a",
            detectInstalledAppAbi(
                nativeLibraryDir = null,
                packagedAbis = setOf("arm64-v8a", "x86_64"),
                supportedAbis = listOf("arm64-v8a"),
            ),
        )
    }

    @Test
    fun `uses a unique versioned destination for every download attempt`() {
        val download = UpdateDownload(
            name = "app-arm64-v8a-release.apk",
            url =
                "https://github.com/YaeNovin/Rikkahub-Revised/releases/download/" +
                    "v2.4.8-revised.3/app-arm64-v8a-release.apk",
            size = "32.0 MB",
        )

        assertEquals(
            "Rikkahub-Revised-v2.4.8-revised.3-1723980000000-app-arm64-v8a-release.apk",
            buildDownloadFileName(download, timestampMillis = 1_723_980_000_000),
        )
        assertEquals(
            "Rikkahub-Revised-v2.4.8-revised.3-1723980000001-app-arm64-v8a-release.apk",
            buildDownloadFileName(download, timestampMillis = 1_723_980_000_001),
        )
    }

    private fun asset(name: String, size: Long = 1_024): GitHubReleaseAsset =
        GitHubReleaseAsset(
            name = name,
            browserDownloadUrl =
                "https://github.com/YaeNovin/Rikkahub-Revised/releases/download/v2.4.8-revised.1/$name",
            size = size,
        )
}
