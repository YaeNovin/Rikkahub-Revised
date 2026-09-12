package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import me.rerere.rikkahub.data.github.GitHubRepository
import me.rerere.rikkahub.data.github.GitHubCardResult
import me.rerere.rikkahub.data.github.GitHubCardStatus
import me.rerere.rikkahub.data.github.GitHubCardFailure
import me.rerere.rikkahub.data.github.GitHubRepositoryUrl
import org.koin.compose.koinInject

@Composable
internal fun GitHubRepositoryCard(url: String, modifier: Modifier = Modifier, linkLabel: String? = null) {
    val fullName = remember(url) { GitHubRepositoryUrl.parse(url) }
    if (fullName == null) return
    val repository: GitHubRepository = koinInject()
    val uriHandler = LocalUriHandler.current
    val view = LocalView.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val export = me.rerere.rikkahub.ui.components.ui.LocalExportContext.current
    var visible by remember(fullName) { mutableStateOf(false) }
    val mayFetch = visible && lifecycleState.isAtLeast(Lifecycle.State.RESUMED) && !export &&
        !LocalRichTextStreaming.current &&
        !me.rerere.rikkahub.ui.components.webview.LocalChatWebPreviewVisible.current
    var result by remember(fullName) { mutableStateOf<GitHubCardResult?>(null) }
    var loading by remember(fullName) { mutableStateOf(true) }
    var attempted by remember(fullName) { mutableStateOf(false) }
    var retry by remember(fullName) { mutableIntStateOf(0) }
    var cacheReadComplete by remember(fullName) { mutableStateOf(false) }
    me.rerere.rikkahub.ui.components.ui.AwaitExportRender(!cacheReadComplete)
    LaunchedEffect(fullName) {
        try {
            repository.observe(fullName).collect { cached ->
                if (cached.data != null || cached.failure != null || cached.status == GitHubCardStatus.RATE_LIMITED) result = cached
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Explicit get/retry still reports failures. */ }
    }
    LaunchedEffect(fullName, mayFetch, export, retry) {
        try {
            result = repository.cached(fullName)
            if (result?.failure != null || result?.status == GitHubCardStatus.RATE_LIMITED) attempted = true
            loading = mayFetch
            if (mayFetch) {
                delay(350) // Scrolling past a card must not spend a request.
                result = repository.get(fullName, forceRefresh = retry > 0)
                attempted = true
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { attempted = true; result = GitHubCardResult(null, GitHubCardStatus.UNAVAILABLE) }
        finally { loading = false; cacheReadComplete = true }
    }
    val retryAt = result?.retryAt ?: 0L
    val retryReady by produceState(false, retryAt, mayFetch) {
        value = false
        if (mayFetch) { delay((retryAt - System.currentTimeMillis()).coerceAtLeast(0)); value = true }
    }
    val colors = richContentColors()
    Surface(
        onClick = { uriHandler.openUri(GitHubRepositoryUrl.url(result?.data?.fullName ?: fullName)) },
        modifier = modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            visible = bounds.width > 0 && bounds.height > 0 && bounds.bottom > 0 && bounds.top < view.height
        },
        shape = MaterialTheme.shapes.large, color = colors.container, border = BorderStroke(1.dp, colors.border),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val item = result?.data
            Text("GitHub · 仓库", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            linkLabel?.takeIf { it.isNotBlank() && !it.equals(fullName, ignoreCase = true) && GitHubRepositoryUrl.parse(it) == null }
                ?.let { Text(it, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Text(item?.fullName ?: fullName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(item?.description?.takeIf(String::isNotBlank) ?: when {
                loading || result == null || !attempted -> "正在读取仓库信息…"
                item == null -> gitHubCardFailureText(result)
                else -> "暂无仓库简介"
            }, style = MaterialTheme.typography.bodyMedium, minLines = 2,
                maxLines = if (item == null && attempted && !loading) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
            if (loading && !export) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (item != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("★ ${item.stars}", style = MaterialTheme.typography.labelMedium)
                    Text("Fork ${item.forks}", style = MaterialTheme.typography.labelMedium)
                    item.language?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                    item.license?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                    if (item.archived) Text("已归档", style = MaterialTheme.typography.labelMedium)
                    if (item.fork) Text("派生仓库", style = MaterialTheme.typography.labelMedium)
                }
                if (result?.status != GitHubCardStatus.FRESH) Text("显示本地缓存", style = MaterialTheme.typography.labelSmall)
            }
            if (attempted && result?.status != GitHubCardStatus.FRESH) {
                if (item != null && (result?.failure != null || result?.status == GitHubCardStatus.RATE_LIMITED)) {
                    Text(gitHubCardFailureText(result), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { retry++ }, enabled = !loading && retryReady) {
                    Text(if (retryReady) "重试加载" else "稍后重试")
                }
            }
        }
    }
}

internal fun gitHubCardFailureText(result: GitHubCardResult?): String = when {
    result?.status == GitHubCardStatus.RATE_LIMITED -> "GitHub API 请求额度受限，冷却结束后可重试；点击卡片仍可打开仓库"
    result?.httpStatus in setOf(404, 410) -> "GitHub API 返回 ${result?.httpStatus}，仓库可能不存在或未公开；点击打开网页"
    result?.httpStatus == 403 -> "GitHub API 拒绝访问（HTTP 403）；网页仍可能正常访问"
    result?.failure == GitHubCardFailure.HTTP -> "GitHub API 返回 HTTP ${result.httpStatus}；可重试或点击打开网页"
    result?.failure == GitHubCardFailure.DNS -> "无法解析 api.github.com，请检查网络后重试"
    result?.failure == GitHubCardFailure.TIMEOUT -> "GitHub API 请求超时，请重试"
    result?.failure == GitHubCardFailure.TLS -> "GitHub API 安全连接失败，请检查网络后重试"
    result?.failure == GitHubCardFailure.INVALID_RESPONSE -> "GitHub API 返回的数据无法解析，可重试或打开网页"
    else -> "无法读取 GitHub API；网页与接口的网络访问情况可能不同，可重试或点击打开网页"
}
