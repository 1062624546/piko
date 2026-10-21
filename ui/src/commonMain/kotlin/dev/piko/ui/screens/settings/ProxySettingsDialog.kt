package dev.piko.ui.screens.settings

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.piko.shared.net.ProxyMode
import dev.piko.shared.net.ProxyProtocol
import dev.piko.shared.net.ProxySetting
import dev.piko.shared.net.RelayProfile
import dev.piko.ui.components.PikoTextField
import dev.piko.ui.platform.LocalPikoPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class ProxyPage { ROOT, WEB, EDITOR, MANUAL }

/** 登录页与设置页共用。列表、代理详情和手动配置分别显示，每次保存直接落盘。 */
@Composable
fun ProxySettingsDialog(
    current: ProxySetting,
    onSave: suspend (ProxySetting) -> Unit,
    onDismiss: () -> Unit,
) {
    var setting by remember { mutableStateOf(current) }
    var page by remember { mutableStateOf(ProxyPage.ROOT) }
    var editing by remember { mutableStateOf<RelayProfile?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun save(next: ProxySetting, then: () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            error = ""
            try {
                onSave(next)
                setting = next
                then()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "保存失败，请重试（凭证加密或存储不可用）" }
            finally { busy = false }
        }
    }
    fun navigate(next: ProxyPage) { if (!busy) { error = ""; page = next } }
    when (page) {
        ProxyPage.ROOT -> SettingsChoiceDialog(
            icon = Icons.Outlined.Public,
            title = "网络代理",
            onDismiss = { if (!busy) onDismiss() },
            footer = error.takeIf { it.isNotBlank() },
        ) {
            SettingsChoiceOption("跟随系统", setting.mode == ProxyMode.SYSTEM,
                { save(setting.copy(mode = ProxyMode.SYSTEM), onDismiss) }, supporting = "使用系统设置中的代理")
            SettingsChoiceOption("不使用代理", setting.mode == ProxyMode.NONE,
                { save(setting.copy(mode = ProxyMode.NONE), onDismiss) }, supporting = "直接连接，保留已添加的代理")
            SettingsNavigationRow(Icons.Outlined.Public, "Web 代理",
                if (setting.mode == ProxyMode.RELAY) "已启用 · ${setting.activeRelay?.name.orEmpty()}" else "添加或管理代理链接",
                onClick = { navigate(ProxyPage.WEB) }, enabled = !busy)
            SettingsNavigationRow(Icons.Outlined.Public, "HTTP / SOCKS5 代理",
                if (setting.mode == ProxyMode.MANUAL) setting.summary() else "手动填写地址和端口",
                onClick = { navigate(ProxyPage.MANUAL) }, enabled = !busy)
        }
        ProxyPage.WEB -> SettingsChoiceDialog(
            icon = Icons.Outlined.Public,
            title = "Web 代理",
            onDismiss = { navigate(ProxyPage.ROOT) },
            dismissLabel = "返回",
            footer = error.takeIf { it.isNotBlank() } ?: "点击已添加的代理，可以测试连接、更换链接或删除。",
            header = {
                TextButton(onClick = { editing = null; navigate(ProxyPage.EDITOR) }, enabled = !busy) { Text("添加 Web 代理") }
            },
        ) {
            if (setting.relays.isEmpty()) {
                Text("还没有添加 Web 代理", style = MaterialTheme.typography.bodyMedium)
            } else {
                SettingsSwitchRow(Icons.Outlined.Public, "启用 Web 代理", null,
                    setting.mode == ProxyMode.RELAY,
                    { enabled -> save(setting.copy(mode = if (enabled) ProxyMode.RELAY else ProxyMode.NONE)) {} },
                    enabled = !busy && setting.activeRelay != null)
                setting.relays.forEach { saved ->
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(
                            selected = setting.mode == ProxyMode.RELAY && setting.selectedRelay == saved.endpoint,
                            onClick = { save(setting.copy(mode = ProxyMode.RELAY, selectedRelay = saved.endpoint, relayMedia = true)) {} },
                            enabled = !busy)
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f)) {
                            SettingsNavigationRow(Icons.Outlined.Public, saved.name,
                                (if (setting.mode == ProxyMode.RELAY && setting.selectedRelay == saved.endpoint) "使用中 · " else "") + saved.endpoint,
                                onClick = { editing = saved; navigate(ProxyPage.EDITOR) }, enabled = !busy)
                        }
                    }
                }

            }
        }
        ProxyPage.EDITOR -> WebProxyEditor(
            profile = editing,
            busy = busy,
            saveError = error,
            onDismiss = { navigate(ProxyPage.WEB) },
            onSave = { next ->
                save(setting.copy(mode = ProxyMode.RELAY,
                    relayMedia = true,
                    relays = setting.relays.filterNot { it.endpoint == editing?.endpoint || it.endpoint == next.endpoint } + next,
                    selectedRelay = next.endpoint)) { page = ProxyPage.WEB }
            },
            onDelete = editing?.let { old -> {
                val remaining = setting.relays.filterNot { it.endpoint == old.endpoint }
                val selected = if (setting.selectedRelay == old.endpoint) remaining.firstOrNull()?.endpoint.orEmpty() else setting.selectedRelay
                save(setting.copy(relays = remaining, selectedRelay = selected,
                    mode = if (remaining.isEmpty() && setting.mode == ProxyMode.RELAY) ProxyMode.NONE else setting.mode)) { page = ProxyPage.WEB }
            } },
        )
        ProxyPage.MANUAL -> ManualProxyEditor(setting, busy, error,
            onDismiss = { navigate(ProxyPage.ROOT) },
            onSave = { next -> save(next, onDismiss) })
    }
}

@Composable
private fun WebProxyEditor(
    profile: RelayProfile?,
    busy: Boolean,
    saveError: String,
    onDismiss: () -> Unit,
    onSave: (RelayProfile) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var link by remember(profile) { mutableStateOf(profile?.let(RelayProfile::exportLink).orEmpty()) }
    var result by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    val parsed = remember(link) { runCatching { RelayProfile.importLink(link) } }
    val target = parsed.getOrNull()
    val scope = rememberCoroutineScope()
    val platform = LocalPikoPlatform.current
    SettingsInputDialog(
        icon = Icons.Outlined.Public,
        title = if (profile == null) "添加 Web 代理" else "管理 Web 代理",
        onDismiss = { if (!busy && !testing) onDismiss() },
        onSave = { target?.let(onSave) },
        saveEnabled = target != null && !busy && !testing,
        footer = "保存后自动启用，所有外部业务请求统一走代理；连接失败不会切换直连。",
    ) {
        PikoTextField(link, { link = it; result = "" }, "代理链接",
            placeholder = "piko://proxy?url=…&token=…",
            enabled = !busy && !testing,
            visualTransformation = PasswordVisualTransformation(),
            isError = link.isNotBlank() && target == null,
            supportingText = if (link.isNotBlank() && target == null) "链接无效，请粘贴完整的 Piko 代理链接" else "粘贴链接后即可测试并保存")
        if (target != null) {
            Text(target.name, style = MaterialTheme.typography.titleSmall)
            Text(target.endpoint, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = {
            val captured = target ?: return@TextButton
            scope.launch {
                testing = true
                result = ""
                try { result = platform.checkRelay(captured) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { result = "连接测试失败，请检查链接和网络" }
                finally { testing = false }
            }
        }, enabled = target != null && !busy && !testing) { Text(if (testing) "正在测试…" else "测试连接") }
        if (result.isNotBlank()) Text(result, style = MaterialTheme.typography.bodyMedium)
        if (saveError.isNotBlank()) Text(saveError, color = MaterialTheme.colorScheme.error)
        if (onDelete != null) TextButton(onClick = onDelete, enabled = !busy && !testing) {
            Text("删除代理", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ManualProxyEditor(
    setting: ProxySetting,
    busy: Boolean,
    error: String,
    onDismiss: () -> Unit,
    onSave: (ProxySetting) -> Unit,
) {
    var protocol by remember { mutableStateOf(setting.protocol) }
    var host by remember { mutableStateOf(setting.host) }
    var port by remember { mutableStateOf(setting.port.takeIf { it > 0 }?.toString().orEmpty()) }
    val draft = setting.copy(mode = ProxyMode.MANUAL, protocol = protocol, host = host.trim(), port = port.toIntOrNull() ?: 0)
    SettingsInputDialog(Icons.Outlined.Public, "HTTP / SOCKS5 代理",
        onSave = { onSave(draft) }, saveEnabled = draft.isManualComplete && !busy,
        onDismiss = { if (!busy) onDismiss() }, footer = error.takeIf { it.isNotBlank() }) {
        SettingsSegmentedChoice(ProxyProtocol.entries, protocol, { it.label }, { protocol = it }, fill = true)
        PikoTextField(host, { host = it }, "地址", placeholder = "127.0.0.1", enabled = !busy)
        PikoTextField(port, { port = it.filter(Char::isDigit).take(5) }, "端口", placeholder = "7890",
            isError = port.isNotEmpty() && draft.port !in 1..65535, supportingText = "1 至 65535", enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    }
}
