package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun `selects preferred device ABI and universal fallback only`() {
        val downloads = selectCompatibleApkDownloads(
            assets = listOf(
                asset("app-x86_64-release.apk", 30L * 1_024 * 1_024),
                asset("app-armeabi-v7a-release.apk", 31L * 1_024 * 1_024),
                asset("app-arm64-v8a-release.apk", 32L * 1_024 * 1_024),
                asset("app-universal-release.apk", 60L * 1_024 * 1_024),
            ),
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        )

        assertEquals(
            listOf("app-arm64-v8a-release.apk", "app-universal-release.apk"),
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
            supportedAbis = listOf("x86"),
        )

        assertEquals(listOf("app-universal-release.apk"), downloads.map { it.name })
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
            supportedAbis = listOf("arm64-v8a"),
        )

        assertEquals(emptyList<UpdateDownload>(), downloads)
    }

    private fun asset(name: String, size: Long = 1_024): GitHubReleaseAsset =
        GitHubReleaseAsset(
            name = name,
            browserDownloadUrl =
                "https://github.com/YaeNovin/Rikkahub-Revised/releases/download/v2.4.8-revised.1/$name",
            size = size,
        )
}
