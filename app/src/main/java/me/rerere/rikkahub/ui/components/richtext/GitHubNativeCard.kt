package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowHardware
import me.rerere.rikkahub.data.github.GitHubRepositoryData

/** GitHub Linguist language colors; unlisted languages use the current theme's outline. */
internal fun gitHubLanguageColor(language: String?): Color? = when (language?.lowercase()) {
    "kotlin" -> Color(0xFFA97BFF); "java" -> Color(0xFFB07219); "python" -> Color(0xFF3572A5)
    "javascript" -> Color(0xFFF1E05A); "typescript" -> Color(0xFF3178C6); "c" -> Color(0xFF555555)
    "c++" -> Color(0xFFF34B7D); "c#" -> Color(0xFF178600); "go" -> Color(0xFF00ADD8)
    "rust" -> Color(0xFFDEA584); "swift" -> Color(0xFFF05138); "dart" -> Color(0xFF00B4AB)
    "ruby" -> Color(0xFF701516); "php" -> Color(0xFF4F5D95); "html" -> Color(0xFFE34C26)
    "css" -> Color(0xFF663399); "vue" -> Color(0xFF41B883); "svelte" -> Color(0xFFFF3E00)
    "shell" -> Color(0xFF89E051); "powershell" -> Color(0xFF012456); "lua" -> Color(0xFF000080)
    "scala" -> Color(0xFFC22D40); "r" -> Color(0xFF198CE7); "julia" -> Color(0xFFA270BA)
    "objective-c" -> Color(0xFF438EFF); "jupyter notebook" -> Color(0xFFDA5B0B)
    "elixir" -> Color(0xFF6E4A7E); "haskell" -> Color(0xFF5E5086); "zig" -> Color(0xFFEC915C)
    else -> null
}

internal const val GITHUB_SOCIAL_IMAGE_WIDTH = 1280
internal const val GITHUB_SOCIAL_IMAGE_HEIGHT = 640

internal fun compactGitHubCount(value: Long): String {
    val (scale, suffix) = when { value >= 1_000_000_000 -> 1_000_000_000.0 to "b"; value >= 1_000_000 -> 1_000_000.0 to "m"; value >= 1000 -> 1000.0 to "k"; else -> return value.toString() }
    return String.format(java.util.Locale.ROOT, "%.1f", value / scale).removeSuffix(".0") + suffix
}

/** Native 2:1 OG composition; a large accessibility font can expand its chat height. */
@Composable
internal fun GitHubNativeCardContent(fullName: String, item: GitHubRepositoryData?, linkLabel: String?, allowAvatarNetwork: Boolean,
    padding: Dp = 0.dp, placeholder: String = "正在读取仓库信息…") {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth().padding(padding).testTag("github-social-layout")) {
        val cardWidth = maxWidth
        val inset = maxWidth * (1f / 15f)
        val avatar = maxWidth * (1f / 6f)
        val barHeight = maxWidth * .02f
        val metrics = buildList {
            item?.language?.let { add(SocialMetricData(it, "Language", null)) }
            item?.openIssuesAndPullRequests?.let { add(SocialMetricData(compactGitHubCount(it), "Issues & PRs", GitHubSocialGlyph.ISSUE, it.toString())) }
            item?.let {
                add(SocialMetricData(compactGitHubCount(it.stars), "Stars", GitHubSocialGlyph.STAR, it.stars.toString()))
                add(SocialMetricData(compactGitHubCount(it.forks), "Forks", GitHubSocialGlyph.FORK, it.forks.toString()))
            }
        }
        val columns = if (fontScale > 1.3f && maxWidth < 500.dp) 2 else metrics.size.coerceAtLeast(1)
        val markSize = (maxWidth.value * .0375f).coerceIn(18f, 28f).dp
        val titleSize = (maxWidth.value * .05f).coerceIn(18f, 34f).sp
        val bodySize = (maxWidth.value * .027f).coerceIn(12f, 18f).sp
        val name = item?.fullName ?: fullName
        val title = remember(name) { buildAnnotatedString {
            append(name.substringBefore('/') + "/")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(name.substringAfter('/')) }
        } }
        Box(Modifier.fillMaxWidth().heightIn(min = maxWidth / 2)) {
            // Measure both sections in normal flow. An overlaid footer with an
            // estimated text height can collide with a wrapped title/description.
            Column(Modifier.fillMaxWidth().heightIn(min = cardWidth / 2), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth().padding(start = inset, end = inset, top = inset),
                horizontalArrangement = Arrangement.spacedBy(inset * .6f), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(inset * .45f)) {
                    Text(title, Modifier.testTag("github-social-title"), color = LocalContentColor.current,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = titleSize, lineHeight = titleSize * 1.18f, fontWeight = FontWeight.Normal),
                        maxLines = if (cardWidth >= 500.dp) 2 else 3, overflow = TextOverflow.Ellipsis)
                    Text(if (item == null) placeholder else item.description?.takeIf(String::isNotBlank) ?: "暂无仓库简介",
                        Modifier.testTag("github-social-description"), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = bodySize, lineHeight = bodySize * 1.4f),
                        maxLines = if (cardWidth >= 500.dp) 2 else 3, overflow = TextOverflow.Ellipsis)
                    val details = listOfNotNull(if (item?.isPrivate == true) "Private" else null,
                        if (item?.archived == true) "Archived" else null, if (item?.fork == true) "Forked" else null,
                        linkLabel?.takeIf { it.isNotBlank() && !it.equals(name, true) && me.rerere.rikkahub.data.github.GitHubRepositoryUrl.parse(it) == null })
                    if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                GitHubSocialAvatar(name, item?.ownerAvatarUrl, avatar, allowAvatarNetwork)
            }
            Row(Modifier.fillMaxWidth().padding(start = inset, end = inset, top = inset * .7f, bottom = inset * .65f + barHeight)
                .testTag("github-social-stats"), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Equal weights avoid rounding a row's last cell onto another
                // line at device densities with fractional dp widths.
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    metrics.chunked(columns).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { metric -> SocialMetric(metric.value, metric.label, metric.glyph, Modifier.weight(1f), cardWidth.value, metric.fullValue) }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                GitHubSocialIcon(GitHubSocialGlyph.GITHUB, Modifier.size(markSize).testTag("github-social-mark"))
            }
            }
            GitHubLanguageBar(item, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(barHeight))
        }
    }
}

@Composable
private fun GitHubSocialAvatar(name: String, url: String?, size: Dp, allowNetwork: Boolean) {
    var ready by remember(url) { mutableStateOf(url == null) }
    var shown by remember(url) { mutableStateOf(false) }
    me.rerere.rikkahub.ui.components.ui.AwaitExportRender(!ready)
    Box(Modifier.size(size).clip(RoundedCornerShape(size * .12f)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
        .testTag("github-social-avatar"), contentAlignment = Alignment.Center) {
        if (!shown) Text(name.take(1).uppercase(), fontSize = (size.value * .46f).sp)
        if (url != null) AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(url).allowHardware(false)
            .apply { if (!allowNetwork) fetcherFactory(CacheOnlyImageFetcher) }
            .networkCachePolicy(if (allowNetwork) CachePolicy.ENABLED else CachePolicy.DISABLED).build(),
            onSuccess = { ready = true; shown = true }, onError = { ready = true; shown = false },
            contentDescription = "仓库所有者头像", modifier = Modifier.fillMaxSize())
    }
}

private data class SocialMetricData(val value: String, val label: String, val glyph: GitHubSocialGlyph?, val fullValue: String = value)

@Composable
private fun SocialMetric(value: String, label: String, glyph: GitHubSocialGlyph?, modifier: Modifier, width: Float, fullValue: String = value) {
    val valueSize = (width * .028f).coerceIn(13f, 20f).sp
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = "$label $fullValue" }.testTag("github-social-${label.lowercase(java.util.Locale.ROOT)}"),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (glyph != null) GitHubSocialIcon(glyph, Modifier.size(16.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontSize = valueSize), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = (width * .021f).coerceIn(10f, 14f).sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GitHubLanguageBar(item: GitHubRepositoryData?, modifier: Modifier) {
    val fallback = MaterialTheme.colorScheme.outlineVariant
    val segments = item?.languageBytes.orEmpty().entries.filter { it.value > 0 }.sortedByDescending { it.value }
    val total = segments.sumOf { it.value.toDouble() }
    val description = if (total > 0) segments.joinToString(", ") { "${it.key} ${String.format(java.util.Locale.ROOT, "%.1f", it.value / total * 100)}%" }
        else "主要语言：${item?.language ?: "未知"}；语言比例暂不可用"
    val colors = segments.map { gitHubLanguageColor(it.key) ?: fallback }
    val accent = gitHubLanguageColor(item?.language) ?: fallback
    Canvas(modifier.testTag("github-social-languages").semantics { contentDescription = description }) {
        if (total <= 0) drawRect(accent)
        else {
            var left = 0f
            segments.forEachIndexed { i, segment ->
                val width = if (i == segments.lastIndex) size.width - left else (size.width * (segment.value / total)).toFloat()
                drawRect(colors[i], Offset(left, 0f), Size(width, size.height)); left += width
            }
        }
    }
}
