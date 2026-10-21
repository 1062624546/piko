package dev.piko.shared.smoke

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.state.ShareSaveState
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ShareNavigationSmokeTest {
    @Test fun returningThroughSeveralFoldersRestoresListsAndExactPositionsWithoutRequests() = smoke { scope ->
        val server = FakePikPakServer()
        val a = server.addFolder("A")
        val b = server.addFolder("B", a.id)
        server.addFile("root.txt")
        server.addFile("parent.txt", parentId = a.id)
        server.addFile("child.txt", parentId = b.id)
        server.shares["S1"] = server.children("").map { it.id }
        val state = ShareSaveState(PikoDriveRepository(server.provider(), MemoryPreferences()), scope, "S1")
        awaitUntil("分享根目录已加载") { !state.isLoading && state.info != null }
        val root = state.entries
        state.rememberFolderView("", 49, 23, "")
        state.enter(root.single { it.id == a.id })
        awaitUntil("进入 A") { !state.isLoading && state.folderId == a.id }
        val parent = state.entries
        state.rememberFolderView(a.id, 12, 41, "B")
        state.enter(parent.single { it.id == b.id })
        awaitUntil("进入 B") { !state.isLoading && state.folderId == b.id }
        val reads = server.shareFolderRequests.get()
        state.goTo(1)
        assertFalse(state.isLoading)
        assertEquals(parent, state.entries)
        assertEquals(ShareSaveState.FolderView(12, 41, "B"), state.folderView(a.id))
        state.goTo(0)
        assertEquals(root, state.entries)
        assertEquals(ShareSaveState.FolderView(49, 23), state.folderView(""))
        assertEquals(reads, server.shareFolderRequests.get(), "返回祖先层级不能重新请求")
    }

    @Test fun restoredHistoryAlsoCachesAncestorsAndPendingChildCannotOverwriteBackNavigation() = smoke { scope ->
        val server = FakePikPakServer()
        val a = server.addFolder("A")
        val b = server.addFolder("B", a.id)
        server.addFolder("C", b.id)
        server.shares["S1"] = listOf(a.id)
        val state = ShareSaveState(PikoDriveRepository(server.provider(), MemoryPreferences()), scope, "S1",
            initialPath = listOf(PikoPathBreadcrumb(a.id, a.name), PikoPathBreadcrumb(b.id, b.name)))
        awaitUntil("历史路径已恢复") { !state.isLoading && state.folderId == b.id }
        val reads = server.shareFolderRequests.get()
        state.goTo(1)
        assertEquals(listOf(b.id), state.entries.map { it.id })
        assertEquals(reads, server.shareFolderRequests.get())
        server.apiDelayMs = 300
        state.enter(state.entries.single())
        awaitUntil("子目录请求已发出") { server.shareFolderRequests.get() > reads }
        state.goTo(0)
        delay(500)
        assertEquals("", state.folderId)
        assertEquals(listOf(a.id), state.entries.map { it.id })
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
    }
}
