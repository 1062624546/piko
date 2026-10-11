package dev.piko.ui.screens.share

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.backhandler.BackHandler
import dev.piko.data.repository.NaturalOrder
import dev.piko.data.repository.isPlayableVideo
import dev.piko.data.repository.isPreviewableImage
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.state.ShareBrowserSession
import dev.piko.ui.components.FolderPickerDialog
import dev.piko.ui.LocalPikoServices
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.launch

/** Independent share browser. Its state lives in PikoServices, not in a bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ShareBrowserScreen(
    session: ShareBrowserSession,
    onBackHome: () -> Unit,
    onVideo: (FileStat) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val downloadManager = LocalPikoServices.current.downloadManager
    val state = session.browser
    var query by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var historyMode by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var previewImage by remember { mutableStateOf<FileStat?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun back() {
        when {
            previewImage != null -> previewImage = null
            historyMode -> historyMode = false
            selecting -> { selecting = false; state?.selectedIds?.clear() }
            state != null && state.path.isNotEmpty() -> state.goTo(state.path.size - 1)
            else -> onBackHome()
        }
    }
    BackHandler { back() }
    val folderId = state?.path?.lastOrNull()?.id.orEmpty()
    val remembered = session.history.firstOrNull { it.shareId == session.activeShareId }
    val startIndex = if (remembered?.path?.lastOrNull()?.id.orEmpty() == folderId) remembered?.scrollIndex ?: 0 else 0
    val scroll = key(folderId) { rememberLazyListState(initialFirstVisibleItemIndex = startIndex) }
    LaunchedEffect(folderId) { query = ""; selecting = false }
    LaunchedEffect(state, folderId, scroll, state?.isLoading) {
        if (state == null || state.isLoading) return@LaunchedEffect
        snapshotFlow { scroll.firstVisibleItemIndex }.collect { index ->
            session.visited(state.info?.title.orEmpty(), state.path.toList(), index)
        }
    }
    LaunchedEffect(state?.path?.toList(), state?.info?.title, state?.isLoading) {
        if (state != null && !state.isLoading) session.visited(state.info?.title.orEmpty(), state.path.toList(), scroll.firstVisibleItemIndex)
    }
    val shown = state?.entries.orEmpty()
        .filter { it.name.contains(query, ignoreCase = true) }
        .sortedWith(compareBy<FileStat> { !it.isFolder }.thenComparator { a, b -> NaturalOrder.compare(a.name, b.name) })
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (historyMode) "浏览过的分享" else state?.info?.title ?: "分享文件") },
            navigationIcon = { IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
            actions = { IconButton(onClick = { historyMode = !historyMode }) { Icon(Icons.Outlined.History, "分享历史") } },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (historyMode) {
                LazyColumn {
                    items(session.history, key = { it.shareId }) { visit ->
                        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f).clickable { session.open(visit.shareId); historyMode = false }) {
                                Text(visit.title.ifBlank { visit.shareId })
                                Text(visit.path.joinToString(" / ") { it.name }.ifBlank { "分享根目录" }, style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { session.removeHistory(visit.shareId) }) { Text("删除") }
                        }
                    }
                }
            } else if (state != null) {
                if (state.needsPassCode) {
                    OutlinedTextField(value = state.passCode, onValueChange = { state.passCode = it }, label = { Text("提取码") })
                    Button(onClick = state::open) { Text("打开分享") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { state.goTo(0) }) { Text("根目录") }
                    state.path.forEachIndexed { index, crumb ->
                        TextButton(onClick = { state.goTo(index + 1) }) { Text(crumb.name, maxLines = 1) }
                    }
                }
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("搜索当前目录") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (state.isLoading) Text("正在读取分享…")
                state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                session.previewError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                notice?.let { Text(it) }
                Row {
                    if (!selecting) TextButton(onClick = { selecting = true }) { Text("多选转存") }
                    else {
                        TextButton(onClick = state::toggleAll) { Text("全选/取消") }
                        TextButton(onClick = { selecting = false; state.selectedIds.clear() }) { Text("取消") }
                        Button(enabled = state.selectedIds.isNotEmpty() && !state.isSaving, onClick = { picking = true }) {
                            Text("转存 " + state.selectedIds.size + " 项")
                        }
                    }
                }
                LazyColumn(state = scroll, modifier = Modifier.weight(1f)) {
                    items(shown, key = { it.id }) { file ->
                        Row(
                            Modifier.fillMaxWidth().combinedClickable(
                                onLongClick = { selecting = true; state.toggle(file) },
                                onClick = {
                                    when {
                                        selecting -> state.toggle(file)
                                        file.isFolder -> state.enter(file)
                                        file.isPlayableVideo() && !preparing -> {
                                            preparing = true
                                            scope.launch {
                                                session.prepareVideo(file)
                                                    .onSuccess { onVideo(file.copy(id = it)) }
                                                    .onFailure { notice = "无法预览：" + it.message }
                                                preparing = false
                                            }
                                        }
                                        file.isPreviewableImage() -> previewImage = file
                                        else -> { selecting = true; state.toggle(file) }
                                    }
                                }
                            ).padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (selecting) Checkbox(checked = file.id in state.selectedIds, onCheckedChange = { state.toggle(file) })
                            Icon(
                                when {
                                    file.isFolder -> Icons.Outlined.Folder
                                    file.isPlayableVideo() -> Icons.Outlined.PlayCircle
                                    file.isPreviewableImage() -> Icons.Outlined.Image
                                    else -> Icons.Outlined.MoreVert
                                }, null
                            )
                            Text(file.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            if (!file.isFolder && file.hash.isNotBlank()) {
                                IconButton(onClick = {
                                    downloadManager.enqueueResolved(
                                        listOf(ResolvedFile(file.name, file.sizeBytes, file.hash)), ""
                                    )
                                    notice = "已加入下载任务：" + file.name
                                }) {
                                    Icon(Icons.Outlined.CloudDownload, "下载到手机")
                                }
                            }
                        }
                    }
                }
                if (preparing) Text("正在准备临时视频…")
            } else {
                Text("请在添加链接中粘贴 PikPak 分享链接")
                TextButton(onClick = { historyMode = true }) { Text("浏览历史") }
            }
        }
    }
    if (picking && state != null) FolderPickerDialog(
        title = "转存到我的网盘", confirmLabel = "转存到这里",
        onDismiss = { picking = false },
        onConfirm = { id, name -> picking = false; state.save(PikoPathBreadcrumb(id, name)) },
    )
    previewImage?.let { file ->
        val images = state?.entries.orEmpty()
            .filter { it.isPreviewableImage() }
            .sortedWith(compareBy(NaturalOrder) { it.name })
        ShareImageViewer(
            images = images,
            initialIndex = images.indexOfFirst { it.id == file.id }.coerceAtLeast(0),
            onDismiss = { previewImage = null },
        )
    }
}
