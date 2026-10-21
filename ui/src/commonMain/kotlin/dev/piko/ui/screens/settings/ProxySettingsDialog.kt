package dev.piko.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.piko.shared.net.RelayProfile
import dev.piko.ui.platform.LocalPikoPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import dev.piko.shared.net.ProxyMode
import dev.piko.shared.net.ProxyProtocol
import dev.piko.shared.net.ProxySetting
import dev.piko.ui.components.PikoTextField

/**
 * 网络代理：跟随系统、不使用或手动填写。登录页与设置页共用，登录页也要有，
 * 因为连不上 PikPak 的人往往就卡在登录这一步。
 */
@Composable
fun ProxySettingsDialog(
    current: ProxySetting,
    onSave: suspend (ProxySetting) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(current.mode) }
    var protocol by remember { mutableStateOf(current.protocol) }
    var host by remember { mutableStateOf(current.host) }
    var port by remember { mutableStateOf(current.port.takeIf { it > 0 }?.toString().orEmpty()) }
    var relays by remember { mutableStateOf(current.relays) }
    var selected by remember { mutableStateOf(current.selectedRelay) }
    var relayMedia by remember { mutableStateOf(current.relayMedia) }
    var importText by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var editingEndpoint by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val platform = LocalPikoPlatform.current
    val draft = current.copy(mode = mode, protocol = protocol, host = host.trim(),
        port = port.toIntOrNull() ?: 0, relays = relays, selectedRelay = selected, relayMedia = relayMedia)
    fun edit(profile: RelayProfile) {
        name = profile.name
        endpoint = profile.endpoint
        token = profile.token
        editingEndpoint = profile.endpoint
        message = ""
    }
    fun profile(): RelayProfile = RelayProfile(name.trim(), RelayProfile.normalizeEndpoint(endpoint), token.trim()).also {
        require(it.isValid) { "请填写名称、HTTPS 根地址及 24 至 256 位访问凭证" }
    }
    fun addProfile() {
        val next = profile()
        relays = relays.filterNot { it.endpoint == editingEndpoint || it.endpoint == next.endpoint } + next
        selected = next.endpoint
        editingEndpoint = null
        name = ""; endpoint = ""; token = ""; importText = ""
        message = "已加入列表，点击保存后生效"
    }
    val portInvalid = port.isNotEmpty() && draft.port !in 1..65535
    val canSave = !busy && when (mode) {
        ProxyMode.MANUAL -> draft.isManualComplete
        ProxyMode.RELAY -> draft.activeRelay != null && listOf(name, endpoint, token, importText).all { it.isBlank() }
        else -> true
    }

    // 选了方式还要填地址才算数，所以是填写类的「取消」「保存」，不是点选即生效
    SettingsInputDialog(
        icon = Icons.Outlined.Public,
        title = "网络代理",
        // 已建立的连接沿用原来的路线，空闲一阵后关闭，之后才换
        footer = "保存后新请求生效。Piko 中继支持登录、文件和分享接口。媒体中继支持 PikPak 域名下的图片与 SDK 原画读取；播放器直链、转码流和第三方域名仍直连。只导入你信任的中继。",
        onSave = {
            scope.launch {
                busy = true
                try { onSave(draft) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { message = "保存失败，请重试（凭证加密或存储不可用）" }
                finally { busy = false }
            }
        },
        saveEnabled = canSave,
        onDismiss = onDismiss,
    ) {
        Column(Modifier.selectableGroup()) {
            SettingsChoiceOption("跟随系统", mode == ProxyMode.SYSTEM, { mode = ProxyMode.SYSTEM }, supporting = "使用系统设置中的代理")
            SettingsChoiceOption("不使用代理", mode == ProxyMode.NONE, { mode = ProxyMode.NONE }, supporting = "始终直接连接")
            SettingsChoiceOption("Piko 中继", mode == ProxyMode.RELAY, { mode = ProxyMode.RELAY }, supporting = "导入专用链接，使用 Cloudflare 中继")
            SettingsChoiceOption("手动", mode == ProxyMode.MANUAL, { mode = ProxyMode.MANUAL }, supporting = "指定 HTTP 或 SOCKS5 代理")
        }
        if (mode == ProxyMode.RELAY) {
            relays.forEach { saved ->
                SettingsChoiceOption(saved.name, selected == saved.endpoint, { selected = saved.endpoint }, supporting = saved.endpoint)
                Row {
                    TextButton(onClick = { edit(saved) }, enabled = !busy) { Text("编辑") }
                    TextButton(onClick = {
                        relays = relays.filterNot { it.endpoint == saved.endpoint }
                        if (selected == saved.endpoint) selected = relays.firstOrNull()?.endpoint.orEmpty()
                        if (editingEndpoint == saved.endpoint) { editingEndpoint = null; name = ""; endpoint = ""; token = "" }
                    }, enabled = !busy) { Text("删除") }
                }
            }
            Row {
                Text("媒体中继（可选）")
                Switch(checked = relayMedia, onCheckedChange = { relayMedia = it })
            }
            PikoTextField(importText, { importText = it }, "代理链接", placeholder = "piko://proxy?url=…&token=…",
                visualTransformation = PasswordVisualTransformation(), supportingText = "链接包含凭证，请勿公开分享")
            TextButton(onClick = {
                try { edit(RelayProfile.importLink(importText)); message = "已解析，请确认后点击添加或更新" }
                catch (e: Exception) { message = e.message ?: "无法解析代理链接" }
            }, enabled = importText.isNotBlank() && !busy) { Text("解析链接") }
            PikoTextField(name, { name = it.take(80) }, "名称", placeholder = "我的 Piko 中继")
            PikoTextField(endpoint, { endpoint = it }, "中继地址", placeholder = "https://piko-relay.example.com")
            PikoTextField(token, { token = it }, "访问凭证", visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Row {
                TextButton(onClick = {
                    try { addProfile() } catch (e: Exception) { message = e.message ?: "配置不完整" }
                }, enabled = !busy) { Text(if (editingEndpoint == null) "添加配置" else "更新配置") }
                TextButton(onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val target = if (endpoint.isNotBlank()) profile() else requireNotNull(draft.activeRelay) { "请先选择或填写配置" }
                            message = platform.checkRelay(target)
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { message = "连接测试失败，请检查配置和网络" }
                        finally { busy = false }
                    }
                }, enabled = !busy) { Text(if (busy) "请稍候…" else "测试连接") }
            }
        }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        if (mode == ProxyMode.MANUAL) {
            SettingsSegmentedChoice(
                options = ProxyProtocol.entries,
                selected = protocol,
                label = { it.label },
                onSelect = { protocol = it },
                fill = true,
            )
            PikoTextField(
                value = host,
                onValueChange = { host = it },
                label = "地址",
                placeholder = "127.0.0.1",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            PikoTextField(
                value = port,
                onValueChange = { value -> port = value.filter(Char::isDigit).take(5) },
                label = "端口",
                placeholder = "7890",
                isError = portInvalid,
                supportingText = "1 至 65535",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
    }
}
