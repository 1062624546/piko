package dev.piko.shared.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.PikoCacheStore
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.PreviewTempFolder
import dev.piko.data.repository.NaturalOrder
import dev.piko.data.repository.isPlayableVideo
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.ResolvedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A share is a browsing session, not a temporary bottom-sheet composable. */
class ShareBrowserSession(
    private val driveRepo: PikoDriveRepository,
    private val previewFolder: PreviewTempFolder,
    private val cache: PikoCacheStore?,
    private val scope: CoroutineScope,
) {
    @Serializable
    data class Crumb(val id: String, val name: String)

    @Serializable
    data class Visit(
        val shareId: String,
        val title: String = "",
        val path: List<Crumb> = emptyList(),
        val scrollIndex: Int = 0,
    )

    var history by mutableStateOf<List<Visit>>(emptyList())
        private set
    var activeShareId by mutableStateOf<String?>(null)
        private set
    var browser by mutableStateOf<ShareSaveState?>(null)
        private set
    var previewOriginalId by mutableStateOf<String?>(null)
        private set
    var autoContinue by mutableStateOf(false)
    var previewFileId by mutableStateOf<String?>(null)
        private set
    var previewError by mutableStateOf<String?>(null)
        private set

    // Pending navigation is consumed by the common app scaffold.
    var openRequested by mutableStateOf<String?>(null)
        private set

    private var owner: String? = null
    private var browserScope: CoroutineScope? = null
    private var saveJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Visit.serializer())

    fun switchAccount(account: String?) {
        if (owner == account) return
        browserScope?.coroutineContext?.get(Job)?.cancel()
        browserScope = null
        browser = null
        activeShareId = null
        previewFileId = null
        openRequested = null
        saveJob?.cancel()
        owner = account
        history = emptyList()
        if (account == null || cache == null) return
        scope.launch {
            val restored = runCatching { cache.read(cacheKey(account))?.let { json.decodeFromString(serializer, it) } }.getOrNull().orEmpty()
            if (owner == account) history = (history + restored).distinctBy { it.shareId }.take(50)
        }
    }

    fun requestOpen(shareId: String) {
        openRequested = shareId
    }

    fun consumeOpenRequest() {
        openRequested = null
    }

    /** Reopening the same share preserves its live folder and selected scroll position. */
    fun open(shareId: String, passCode: String = "") {
        if (activeShareId == shareId && browser != null) return
        browserScope?.coroutineContext?.get(Job)?.cancel()
        val saved = history.firstOrNull { it.shareId == shareId }
        val child = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        browserScope = child
        activeShareId = shareId
        browser = ShareSaveState(
            driveRepo, child, shareId, initialPassCode = passCode,
            initialPath = saved?.path?.map { PikoPathBreadcrumb(it.id, it.name) }.orEmpty(),
        )
        history = (listOf(saved ?: Visit(shareId)) + history.filterNot { it.shareId == shareId }).take(50)
        save()
    }

    fun visited(title: String, path: List<PikoPathBreadcrumb>, scrollIndex: Int) {
        val id = activeShareId ?: return
        val old = history.firstOrNull { it.shareId == id } ?: Visit(id)
        val updated = old.copy(
            title = title.ifBlank { old.title },
            path = path.map { Crumb(it.id, it.name) },
            scrollIndex = scrollIndex.coerceAtLeast(0),
        )
        if (updated == old) return
        history = history.map { if (it.shareId == id) updated else it }
        save()
    }

    fun removeHistory(id: String) {
        history = history.filterNot { it.shareId == id }
        save()
    }

    private fun save() {
        val store = cache ?: return
        val account = owner ?: return
        val snapshot = history
        saveJob?.cancel()
        saveJob = scope.launch {
            runCatching { store.write(cacheKey(account), json.encodeToString(serializer, snapshot)) }
        }
    }

    /** A preview is a temporary copy, never the original shared file. */
    suspend fun prepareVideo(file: FileStat): Result<String> {
        if (file.isFolder || file.hash.isBlank()) {
            return Result.failure(IllegalArgumentException("此文件无法临时预览"))
        }
        val cleared = releasePreview()
        if (cleared.isFailure) return Result.failure(cleared.exceptionOrNull()!!)
        val prepared = previewFolder.put(ResolvedFile(file.name, file.sizeBytes, file.hash))
        prepared.onSuccess {
            previewOriginalId = file.id
            previewFileId = it
            previewError = null
        }.onFailure { previewError = "无法预览：${it.message}" }
        return prepared
    }

    /** Playback order always follows natural file names in the current shared folder. */
    fun neighborVideo(offset: Int): FileStat? {
        val source = previewOriginalId ?: return null
        val playable = browser?.entries.orEmpty().filter { it.isPlayableVideo() && it.hash.isNotBlank() }
            .sortedWith(compareBy(NaturalOrder) { it.name })
        val index = playable.indexOfFirst { it.id == source }
        return if (index < 0) null else playable.getOrNull(index + offset)
    }

    /** Delete only the exact file created for this preview, never the user's stored videos. */
    suspend fun releasePreview(): Result<Unit> {
        val id = previewFileId ?: return Result.success(Unit)
        val parent = previewFolder.folderId().getOrElse { return Result.failure(it) }
        val detail = driveRepo.getFileDetail(id).getOrElse {
            previewError = "检查临时文件失败，尚未删除：${it.message}"
            return Result.failure(it)
        }
        if (detail.parentId != parent) {
            val error = IllegalStateException("临时文件位置已改变，为防止误删已停止清理")
            previewError = error.message
            return Result.failure(error)
        }
        return driveRepo.delete(listOf(id)).onSuccess {
            if (previewFileId == id) previewFileId = null
            previewError = null
        }.onFailure { previewError = "删除临时视频失败：${it.message}" }
    }

    fun releasePreviewInBackground() {
        scope.launch {
            kotlinx.coroutines.delay(500L)
            releasePreview()
        }
    }

    fun clearPreviewError() {
        previewError = null
    }

    private fun cacheKey(account: String) =
        "share-history-" + account.replace(Regex("""[^A-Za-z0-9._@-]"""), "_") + ".json"
}
