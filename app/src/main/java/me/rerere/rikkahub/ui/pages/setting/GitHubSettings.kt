package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.github.*
import me.rerere.rikkahub.ui.components.richtext.gitHubCardFailureText
import org.koin.compose.koinInject

@Composable
internal fun GitHubSettings(display: DisplaySetting, onUpdate: (DisplaySetting) -> Unit) {
    val store: GitHubTokenStore = koinInject()
    val repository: GitHubRepository = koinInject()
    val state by store.state.collectAsStateWithLifecycle()
    val quota by repository.quota.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    // Never save the field in SavedState or prefill it with the decrypted token.
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var anonymous by remember(display.gitHubAnonymousHourlyBudget) { mutableStateOf(display.gitHubAnonymousHourlyBudget.toString()) }
    var authenticated by remember(display.gitHubAuthenticatedHourlyBudget) { mutableStateOf(display.gitHubAuthenticatedHourlyBudget.toString()) }
    LaunchedEffect(store) { store.read() }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("GitHub 仓库卡片", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("新版原生卡片")
                Text("关闭后使用原有卡片样式", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = display.gitHubCardStyle == GitHubCardStyle.NATIVE,
                onCheckedChange = { onUpdate(display.copy(gitHubCardStyle = if (it) GitHubCardStyle.NATIVE else GitHubCardStyle.CLASSIC)) })
        }
        Text(when { state.unreadable -> "本机密钥不可用，请重新填写或移除 Token"; state.configured -> "Token 已保存在此设备"; else -> "未填写 Token，使用匿名公开仓库预览" },
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text(if (state.configured) "替换 GitHub Token" else "GitHub Token") },
            modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !busy && input.isNotBlank(), onClick = {
                val value = input
                busy = true
                scope.launch {
                    try { store.save(value); input = ""; message = "Token 已通过 Android Keystore 加密保存" }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message = "无法保存，请检查 Token 格式和本机密钥状态" }
                    finally { busy = false }
                }
            }) { Text("保存 Token") }
            TextButton(enabled = !busy && state.configured && !state.unreadable, onClick = {
                busy = true
                scope.launch {
                    try {
                        val result = repository.get("YaeNovin/Rikkahub-Revised", validate = true)
                        message = if (result.status == GitHubCardStatus.FRESH) "鉴权验证成功；私有仓库仍需 Token 对相应仓库有读取权限" else gitHubCardFailureText(result)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message = "暂时无法验证 GitHub 连接" }
                    finally { busy = false }
                }
            }) { Text("验证 Token") }
            TextButton(enabled = !busy && state.configured, onClick = {
                busy = true
                scope.launch {
                    try { store.clear(); input = ""; message = "Token 已移除，恢复匿名访问" }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message = "Token 移除失败，请重试" }
                    finally { busy = false }
                }
            }) { Text("移除 Token") }
            TextButton(onClick = { uri.openUri("https://github.com/settings/personal-access-tokens/new") }) { Text("前往 GitHub 获取") }
        }
        Text("推荐使用细粒度个人 Token。私有仓库需选择目标仓库并允许 Metadata 读取，无需写入权限。Token 只用于 GitHub API，不随备份或 Web 配置导出。",
            style = MaterialTheme.typography.bodySmall)
        Text("每小时本地请求预算", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = anonymous, onValueChange = { anonymous = it.take(4) }, label = { Text("匿名 1–60") },
                modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(value = authenticated, onValueChange = { authenticated = it.take(5) }, label = { Text("鉴权 1–5000") },
                modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        TextButton(onClick = {
            val a = anonymous.toIntOrNull(); val b = authenticated.toIntOrNull()
            if (a == null || a !in 1..60 || b == null || b !in 1..5000) message = "请填写范围内的整数"
            else { onUpdate(display.copy(gitHubAnonymousHourlyBudget = a, gitHubAuthenticatedHourlyBudget = b)); message = "预算已更新；仍遵守 GitHub 服务端限流" }
        }) { Text("应用预算") }
        Text("GitHub 通常提供匿名每小时 60 次、个人 Token 每小时 5000 次，额度会与其他客户端共享；本地默认分别为 50 和 4500，次级限流仍可能发生。",
            style = MaterialTheme.typography.bodySmall)
        quota?.let {
            Text("最近请求：本地 ${it.localUsed}/${it.localLimit}；服务端剩余 ${it.remaining ?: "未知"}/${it.serverLimit ?: "未知"}" +
                (it.resetAt?.let { reset -> "，重置于 ${java.text.DateFormat.getTimeInstance().format(java.util.Date(reset))}" } ?: ""),
                style = MaterialTheme.typography.bodySmall)
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
