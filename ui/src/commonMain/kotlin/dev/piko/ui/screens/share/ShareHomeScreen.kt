package dev.piko.ui.screens.share

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.state.ShareBrowserSession
import dev.piko.ui.screens.instant.InstantSheetContent

/**
 * Mobile top-level share tab: new links and recently visited shares are visible
 * without first opening the drive's add-link bottom sheet.
 * Magnet/other links retain the existing InstantSheetState workbench.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareHomeScreen(
    session: ShareBrowserSession,
    instantState: InstantSheetState?,
    onOpenShare: (String) -> Unit,
    onOpenHistory: (String) -> Unit,
    onAddOtherLink: (String) -> Unit,
    onCloseInstant: () -> Unit,
    onPreviewInstant: (String, String) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("分享") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Link, contentDescription = null)
                        Text("打开分享链接", style = MaterialTheme.typography.titleMedium)
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it; error = null },
                        label = { Text("粘贴 PikPak 分享链接或磁力链接") },
                        placeholder = { Text("https://mypikpak.com/s/...") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val value = input.trim()
                            if (value.isBlank()) {
                                error = "请先输入分享链接"
                            } else if (InstantSheetState.findShareLink(value) != null) {
                                onOpenShare(value)
                            } else {
                                onAddOtherLink(value)
                            }
                        },
                    ) {
                        Text("打开链接")
                    }
                }
            }

            if (instantState != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("添加链接任务", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onCloseInstant) { Text("收起") }
                }
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                    InstantSheetContent(
                        state = instantState,
                        inSideSheet = true,
                        onPreview = onPreviewInstant,
                    )
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("最近浏览", style = MaterialTheme.typography.titleMedium)
                    Text(session.history.size.toString() + " 条", style = MaterialTheme.typography.labelMedium)
                }
                if (session.history.isEmpty()) {
                    Text("还没有浏览记录。打开分享链接后，下次可以直接从这里继续。", style = MaterialTheme.typography.bodyMedium)
                }
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(session.history, key = { it.shareId }) { item ->
                        Card(Modifier.fillMaxWidth().clickable { onOpenHistory(item.shareId) }) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(item.title.ifBlank { "PikPak 分享" }, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        item.path.joinToString(" / ") { it.name }.ifBlank { "从分享根目录继续" },
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                    )
                                }
                                IconButton(onClick = { session.removeHistory(item.shareId) }) {
                                    Icon(Icons.Outlined.History, contentDescription = "删除历史")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
