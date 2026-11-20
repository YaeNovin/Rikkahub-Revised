package me.rerere.rikkahub.ui.components.richtext

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.github.GitHubRepositoryData
import me.rerere.rikkahub.ui.components.ui.BitmapComposer
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.getActivity

/** An immutable metadata snapshot, rendered at the official recommended OG dimensions. */
@Composable
internal fun rememberGitHubSocialImageRenderer(fullName: String, data: GitHubRepositoryData?, label: String?): suspend () -> Bitmap {
    val context = LocalContext.current
    val settings = LocalSettings.current
    val scheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    return {
        BitmapComposer().composableToBitmap(requireNotNull(context.getActivity()), width = 640.dp, height = 320.dp,
            screenDensity = Density(2f, 1f)) {
            CompositionLocalProvider(LocalSettings provides settings) {
                MaterialTheme(colorScheme = scheme, typography = typography) {
                    Surface(Modifier.fillMaxSize(), color = scheme.surface, contentColor = scheme.onSurface) {
                        GitHubNativeCardContent(fullName, data, label, false)
                    }
                }
            }
        }.also { check(it.width == GITHUB_SOCIAL_IMAGE_WIDTH && it.height == GITHUB_SOCIAL_IMAGE_HEIGHT) }
    }
}
