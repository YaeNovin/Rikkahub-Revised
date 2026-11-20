package me.rerere.rikkahub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.github.*
import me.rerere.rikkahub.ui.components.richtext.GitHubRepositoryCard
import me.rerere.rikkahub.ui.components.richtext.GitHubNativeCardContent
import me.rerere.rikkahub.ui.components.richtext.rememberGitHubSocialImageRenderer
import me.rerere.rikkahub.ui.context.LocalSettings
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.ui.pages.setting.GitHubSettings

class GitHubCardsUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ComponentActivity

    @Before fun launchHostDirectly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val component = instrumentation.targetContext.packageName + "/androidx.activity.ComponentActivity"
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -f 0x10008000 -n $component"
        )).use { it.readBytes() }
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!::host.isInitialized && System.nanoTime() < deadline) {
            instrumentation.runOnMainSync {
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<ComponentActivity>().firstOrNull()?.let { host = it }
            }
            if (!::host.isInitialized) Thread.sleep(100)
        }
        check(::host.isInitialized) { "GitHub card fixture did not resume" }
    }

    @After fun closeHost() {
        if (::host.isInitialized) InstrumentationRegistry.getInstrumentation().runOnMainSync { host.finish() }
    }

    @Test fun nativeAndClassicCardsSupportThemesTapAndThreeActionMenu() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor {
            calls.incrementAndGet()
            val body = if (it.request().url.encodedPath.endsWith("/languages")) """{"Kotlin":700,"Java":200,"Shell":100}"""
                else """{"full_name":"Owner/Repo","private":false,"description":"Native card fixture with readable description","language":"Kotlin","stargazers_count":123,"forks_count":7,"open_issues_count":25,"license":{"spdx_id":"MIT"},"fork":false,"archived":false}"""
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repository = GitHubRepository(database.githubRepositoryCacheDao(), client)
        var dark by mutableStateOf(false)
        var style by mutableStateOf(GitHubCardStyle.NATIVE)
        var opened: String? = null
        var clearTestCredential = false
        val tokenStore = org.koin.core.context.GlobalContext.get().get<GitHubTokenStore>()
        val photosBefore = ownPhotos()
        val sharesBefore = File(context.cacheDir, "github-card-shares").listFiles()?.mapTo(hashSetOf()) { it.name }.orEmpty()
        try {
            compose.runOnUiThread { host.setContent {
                val settings = Settings(displaySetting = DisplaySetting(gitHubCardStyle = style))
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    CompositionLocalProvider(LocalSettings provides settings, LocalUriHandler provides object : UriHandler {
                        override fun openUri(uri: String) { opened = uri }
                    }) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            SelectionContainer {
                                Column(Modifier.padding(20.dp)) { GitHubRepositoryCard("https://github.com/owner/repo", repository = repository) }
                            }
                        }
                    }
                }
            } }
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Native card fixture with readable description").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(10_000) { runBlocking { repository.cached("owner/repo").data?.languageBreakdownComplete == true } }
            compose.onNodeWithText("Stars").assertIsDisplayed()
            compose.onNodeWithText("Forks").assertIsDisplayed()
            compose.onNodeWithTag("github-repository-card").performClick()
            assertEquals("https://github.com/owner/repo", opened)
            val output = File(context.getExternalFilesDir(null), "github-card-qa").apply { mkdirs() }
            for (darkMode in listOf(false, true)) {
                for (cardStyle in listOf(GitHubCardStyle.NATIVE, GitHubCardStyle.CLASSIC)) {
                    compose.runOnIdle { dark = darkMode; style = cardStyle }
                    compose.waitForIdle()
                    compose.onNodeWithText("Owner/Repo").assertIsDisplayed()
                    compose.onNodeWithText("Kotlin").assertIsDisplayed()
                    compose.onNodeWithTag("github-repository-card").captureToImage().asAndroidBitmap()
                        .let { image -> File(output, "${cardStyle.name.lowercase()}-${if (darkMode) "dark" else "light"}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                }
            }
            compose.runOnIdle { style = GitHubCardStyle.NATIVE }
            compose.waitForIdle()
            compose.onNodeWithTag("github-repository-card").performTouchInput { longClick() }
            compose.onNodeWithText("分享").assertIsDisplayed()
            compose.onNodeWithText("预览卡片").assertIsDisplayed().performClick()
            compose.onNodeWithText("卡片预览").assertIsDisplayed()
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("GitHub 社交预览卡片").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("关闭").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("github-repository-card").performTouchInput { longClick() }
            compose.onNodeWithText("保存至相册").assertIsDisplayed()
            compose.onNodeWithText("分享").assertIsDisplayed()
            assertEquals("Preview must reuse metadata and languages", 2, calls.get())
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                compose.onNodeWithText("保存至相册").performClick()
                compose.waitUntil(15_000) { (ownPhotos() - photosBefore).isNotEmpty() && compose.onAllNodesWithText("保存至相册").fetchSemanticsNodes().isEmpty() }
                val photo = (ownPhotos() - photosBefore).single()
                val decoded = context.contentResolver.openInputStream(photo).use { BitmapFactory.decodeStream(it) }
                assertNotNull("Gallery PNG must decode", decoded)
                assertEquals(1280, decoded.width); assertEquals(640, decoded.height)
                assertEquals(0xFFA97BFF.toInt(), decoded.getPixel(448, 636))
                assertEquals(0xFFB07219.toInt(), decoded.getPixel(1024, 636))
                assertEquals(0xFF89E051.toInt(), decoded.getPixel(1216, 636))
                File(output, "social-export-dark.png").outputStream().use { decoded.compress(Bitmap.CompressFormat.PNG, 100, it) }
                decoded.recycle()
                compose.onNodeWithTag("github-repository-card").performTouchInput { longClick() }
            }
            compose.onNodeWithText("分享").performClick()
            val deadline = System.nanoTime() + 10_000_000_000L
            var chooser = false
            while (!chooser && System.nanoTime() < deadline) {
                chooser = shell("dumpsys activity activities").lineSequence()
                    .any { it.contains("topResumedActivity") && it.contains("ChooserActivity") }
                if (!chooser) Thread.sleep(100)
            }
            shell("input keyevent KEYCODE_BACK")
            assertTrue("Image sharing should open the system chooser", chooser)
            assertTrue(File(context.cacheDir, "github-card-shares").listFiles().orEmpty().any { it.name !in sharesBefore && it.length() > 100 })

            // Exercise the production settings entry and Koin wiring with a synthetic token.
            assertFalse("Never replace a pre-existing credential", tokenStore.state.value.configured)
            clearTestCredential = true
            var display by mutableStateOf(DisplaySetting())
            compose.runOnUiThread { host.setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalSettings provides Settings(displaySetting = display), LocalUriHandler provides object : UriHandler {
                        override fun openUri(uri: String) { opened = uri }
                    }) {
                        Surface { Column { GitHubSettings(display) { display = it } } }
                    }
                }
            } }
            compose.onNode(hasSetTextAction() and hasText("GitHub Token")).performTextInput("synthetic-ui-credential")
            compose.onNodeWithText("保存 Token").performClick()
            compose.waitUntil(10_000) { tokenStore.state.value.configured }
            compose.onNodeWithText("移除 Token").performClick()
            compose.waitUntil(10_000) { !tokenStore.state.value.configured }
            compose.onNodeWithText("前往 GitHub 获取").performClick()
            assertEquals("https://github.com/settings/personal-access-tokens/new", opened)
            assertEquals("Settings and export must not fetch repository metadata", 2, calls.get())
        } finally {
            if (clearTestCredential) runBlocking { tokenStore.clear() }
            (ownPhotos() - photosBefore).forEach { context.contentResolver.delete(it, null, null) }
            File(context.cacheDir, "github-card-shares").listFiles()?.filter { it.name !in sharesBefore }?.forEach { it.delete() }
            database.close()
        }
    }

    @Test fun socialLayoutAdaptsToLongNamesAndLargeFonts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "github-card-qa").apply { mkdirs() }
        var scale by mutableFloatStateOf(1f)
        var longName by mutableStateOf(true)
        var dark by mutableStateOf(false)
        var render: (suspend () -> Bitmap)? = null
        val sample = GitHubRepositoryData("chaitanyagiri/munder-difflin", "local multi-agent harness", "Kotlin", 4000, 393, null, false, false,
            openIssuesAndPullRequests = 25, languageBytes = mapOf("Kotlin" to 700L, "Java" to 200L, "Shell" to 100L), languageBreakdownComplete = true)
        compose.runOnUiThread { host.setContent {
            val density = LocalDensity.current
            val item = if (longName) sample.copy(fullName = "research-and-development-team/" + "very-long-repository-name-".repeat(3), description = "长简介用于验证大字体下标题、头像、简介和统计数据互不遮挡。".repeat(4)) else sample
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale), LocalSettings provides Settings()) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme(surface = Color.White)) {
                    val snapshot = rememberGitHubSocialImageRenderer(sample.fullName, sample, null)
                    SideEffect { render = snapshot }
                    Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(20.dp)) { GitHubNativeCardContent(item.fullName, item, null, false) } }
                }
            }
        } }
        for (font in listOf(1f, 1.6f, 2f)) {
            compose.runOnIdle { scale = font }
            compose.waitForIdle()
            val title = compose.onNodeWithTag("github-social-title").getUnclippedBoundsInRoot()
            val avatar = compose.onNodeWithTag("github-social-avatar").getUnclippedBoundsInRoot()
            val desc = compose.onNodeWithTag("github-social-description").getUnclippedBoundsInRoot()
            val stats = compose.onNodeWithTag("github-social-stats").getUnclippedBoundsInRoot()
            val bar = compose.onNodeWithTag("github-social-languages").getUnclippedBoundsInRoot()
            compose.onNodeWithTag("github-social-layout").captureToImage().asAndroidBitmap().let { image ->
                File(output, "social-long-font-$font.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            assertTrue("Title and avatar overlap at font $font: $title / $avatar", title.right <= avatar.left)
            assertTrue("Description and statistics overlap at font $font: $desc / $stats", desc.bottom <= stats.top)
            assertTrue("Statistics and language bar overlap at font $font: $stats / $bar", stats.bottom <= bar.top)
        }
        for (isDark in listOf(false, true)) {
            compose.runOnIdle { longName = false; dark = isDark; scale = 1f }
            compose.waitForIdle()
            val image = runBlocking { requireNotNull(render).invoke() }
            try {
                assertEquals(1280, image.width); assertEquals(640, image.height)
                File(output, "social-reference-${if (isDark) "dark" else "light"}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { image.recycle() }
        }
    }

    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun ownPhotos(): Set<Uri> {
        if (android.os.Build.VERSION.SDK_INT < 29) return emptySet()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return buildSet {
            context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
                arrayOf("GitHub_%.png", context.packageName), null)?.use { cursor ->
                while (cursor.moveToNext()) add(android.content.ContentUris.withAppendedId(collection, cursor.getLong(0)))
            }
        }
    }
}
