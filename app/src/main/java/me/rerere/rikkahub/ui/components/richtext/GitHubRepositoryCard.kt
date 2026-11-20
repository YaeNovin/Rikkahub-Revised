package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
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
internal fun GitHubRepositoryCard(url: String, modifier: Modifier = Modifier, linkLabel: String? = null,
    repository: GitHubRepository = koinInject()) {
    val fullName = remember(url) { GitHubRepositoryUrl.parse(url) }
    if (fullName == null) return
    val credentialState by repository.credentialState.collectAsState()
    val style = me.rerere.rikkahub.ui.context.LocalSettings.current.displaySetting.gitHubCardStyle
    val native = style == me.rerere.rikkahub.data.github.GitHubCardStyle.NATIVE
    val layer = rememberGraphicsLayer()
    var actions by remember(fullName) { mutableStateOf(false) }
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
                result = cached
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Explicit get/retry still reports failures. */ }
    }
    LaunchedEffect(fullName, mayFetch, export, retry, credentialState.revision, native) {
        try {
            result = repository.cached(fullName)
            if (result?.failure != null || result?.status == GitHubCardStatus.RATE_LIMITED) attempted = true
            loading = mayFetch
            if (mayFetch) {
                delay(350) // Scrolling past a card must not spend a request.
                result = repository.get(fullName, forceRefresh = retry > 0, enrichLanguages = native)
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
    val socialImage = rememberGitHubSocialImageRenderer(fullName, result?.data, linkLabel)
    DisableSelection {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("github-repository-card").drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        }.combinedClickable(enabled = !export,
            onClick = { uriHandler.openUri(GitHubRepositoryUrl.url(result?.data?.fullName ?: fullName)) },
            onLongClick = { actions = true }, onClickLabel = "打开 GitHub 仓库", onLongClickLabel = "仓库卡片操作"
        ).onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            visible = bounds.width > 0 && bounds.height > 0 && bounds.bottom > 0 && bounds.top < view.height
        },
        shape = if (native) RoundedCornerShape(6.dp) else MaterialTheme.shapes.large,
        color = colors.container, border = BorderStroke(1.dp, colors.border),
    ) {
        Column(Modifier.padding(if (native) 0.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(if (native) 0.dp else 6.dp)) {
            val item = result?.data
            if (native) GitHubNativeCardContent(fullName, item, linkLabel, mayFetch, padding = 0.dp,
                placeholder = if (loading || !attempted) "正在读取仓库信息…" else gitHubCardFailureText(result))
            else {
            Text("GitHub · 仓库", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            linkLabel?.takeIf { it.isNotBlank() && !it.equals(fullName, ignoreCase = true) && GitHubRepositoryUrl.parse(it) == null }
                ?.let { Text(it, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Text(item?.fullName ?: fullName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item?.isPrivate == true) Text("Private", style = MaterialTheme.typography.labelSmall)
            }
            if (!native) {
            Text(item?.description?.takeIf(String::isNotBlank) ?: when {
                loading || result == null || !attempted -> "正在读取仓库信息…"
                item == null -> gitHubCardFailureText(result)
                else -> "暂无仓库简介"
            }, style = MaterialTheme.typography.bodyMedium, minLines = 2,
                maxLines = if (item == null && attempted && !loading) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
            }
            if (loading && !export && result?.data == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (item != null) {
                if (!native) FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
    GitHubCardActions(actions, onDismiss = { actions = false },
        GitHubRepositoryUrl.url(result?.data?.fullName ?: fullName), layer, renderImage = if (native) socialImage else null) {
        Surface(shape = if (native) RoundedCornerShape(6.dp) else MaterialTheme.shapes.large,
            color = colors.container, border = BorderStroke(1.dp, colors.border)) {
            // Read-only snapshot: opening the preview spends no API requests.
            if (native) GitHubNativeCardContent(fullName, result?.data, linkLabel, false)
            else Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val item = result?.data
                Text("GitHub · 仓库", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                linkLabel?.takeIf { it.isNotBlank() && !it.equals(fullName, true) && GitHubRepositoryUrl.parse(it) == null }
                    ?.let { Text(it, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Text(item?.fullName ?: fullName, style = MaterialTheme.typography.titleSmall)
                if (item?.isPrivate == true) Text("Private", style = MaterialTheme.typography.labelSmall)
                Text(item?.description ?: if (item == null) gitHubCardFailureText(result) else "暂无仓库简介", style = MaterialTheme.typography.bodyMedium, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                item?.let {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("★ ${it.stars}", style = MaterialTheme.typography.labelMedium)
                        Text("Fork ${it.forks}", style = MaterialTheme.typography.labelMedium)
                        it.language?.let { language -> Text(language, style = MaterialTheme.typography.labelMedium) }
                        it.license?.let { license -> Text(license, style = MaterialTheme.typography.labelMedium) }
                        if (it.archived) Text("已归档", style = MaterialTheme.typography.labelMedium)
                        if (it.fork) Text("派生仓库", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
    }
}

internal fun gitHubCardFailureText(result: GitHubCardResult?): String = when {
    result?.failure == GitHubCardFailure.CREDENTIALS_UNAVAILABLE -> "GitHub Token 的本机密钥不可用，请在常规设置中重新填写或移除 Token"
    result?.failure == GitHubCardFailure.AUTHENTICATION -> "GitHub Token 无效、已过期或被撤销（HTTP 401），请在常规设置中替换或移除"
    result?.rateLimitKind == me.rerere.rikkahub.data.github.GitHubRateLimitKind.LOCAL -> "已达到本地每小时请求预算，可等待重置或在常规设置调整预算"
    result?.rateLimitKind == me.rerere.rikkahub.data.github.GitHubRateLimitKind.PRIMARY -> "GitHub 服务端每小时额度已用尽，请等待额度重置"
    result?.rateLimitKind == me.rerere.rikkahub.data.github.GitHubRateLimitKind.SECONDARY -> "GitHub 服务端触发次级限流，请等待冷却后重试"
    result?.status == GitHubCardStatus.RATE_LIMITED -> "GitHub API 请求额度受限，冷却结束后可重试；点击卡片仍可打开仓库"
    result?.httpStatus in setOf(404, 410) -> "GitHub API 返回 ${result?.httpStatus}，仓库不存在、未公开或 Token 无读取权限；私有仓库需在常规设置填写有权限的 Token，仍可点击打开网页"
    result?.httpStatus == 403 -> "GitHub API 拒绝访问（HTTP 403）；网页仍可能正常访问"
    result?.failure == GitHubCardFailure.HTTP -> "GitHub API 返回 HTTP ${result.httpStatus}；可重试或点击打开网页"
    result?.failure == GitHubCardFailure.DNS -> "无法解析 api.github.com，请检查网络后重试"
    result?.failure == GitHubCardFailure.TIMEOUT -> "GitHub API 请求超时，请重试"
    result?.failure == GitHubCardFailure.TLS -> "GitHub API 安全连接失败，请检查网络后重试"
    result?.failure == GitHubCardFailure.INVALID_RESPONSE -> "GitHub API 返回的数据无法解析，可重试或打开网页"
    else -> "无法读取 GitHub API；网页与接口的网络访问情况可能不同，可重试或点击打开网页"
}
