package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.sessioncatalog.DefaultSessionCatalogViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.*
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.app.ui.compose.rememberComposeSessionCatalogRenderer
import io.github.stream29.kodex.cli.sessioncatalog.*
import kotlinx.coroutines.*
import kotlin.test.*
import kotlin.time.Instant

val offscreenSessionCatalogTest by testSuite {
    test("catalog mounts once and renders a bounded lazy viewport rather than an empty loading result") { offscreenTest {
        val ports = CatalogPorts().apply {
            entries = List(1_000) { SessionCatalogEntry(it, "row${it.toString().padStart(4, '0')}") }
            loadGate = CompletableDeferred()
        }
        withCatalogFixture(ports) { vm ->
            val redraw = mutableStateOf(0)
            val scene = OffscreenScene.create {
                UiText("draw=${redraw.value}")
                SessionCatalogContent(vm, rememberComposeSessionCatalogRenderer(), { throw it })
            }
            try {
                scene.settle { scene.hasText("Loading sessions…") }
                assertFalse(scene.hasText("No persisted sessions"))
                assertEquals(listOf("load:false"), ports.calls)
                redraw.value++
                scene.settle { scene.hasText("draw=1") }
                assertEquals(listOf("load:false"), ports.calls)
                ports.loadGate!!.complete(Unit)
                scene.settle { scene.hasText("row0000") }
                assertFalse(scene.hasText("row0999"))
                assertTrue(scene.catalogRows() in 1..12)
                val scroll = scene.nodes().mapNotNull { it.config.getOrNull(SemanticsActions.ScrollToIndex)?.action }.single()
                assertTrue(scroll(999))
                scene.settle { scene.hasText("row0999") }
                assertFalse(scene.hasText("row0000"))
                assertTrue(scene.catalogRows() in 1..12)
                scene.frame()
            } finally { scene.close() }
            ports.loadGate = null
            ports.entries = emptyList()
            val empty = OffscreenScene.create { SessionCatalogContent(vm, rememberComposeSessionCatalogRenderer(), { throw it }) }
            try { empty.settle { empty.hasText("No persisted sessions") }; empty.frame() } finally { empty.close() }
            assertEquals(listOf("load:false", "load:false"), ports.calls)
        }
    } }

    test("archive filter and fork use the real menu while primary open dismisses only after completion") { offscreenTest {
        val ports = CatalogPorts()
        withCatalogFixture(ports) { vm ->
            val visible = mutableStateOf(true)
            ports.onDismiss = { visible.value = false }
            val scene = OffscreenScene.create {
                if (visible.value) SessionCatalogContent(vm, rememberComposeSessionCatalogRenderer(), { throw it })
                else UiText("closed")
            }
            try {
                scene.settle { scene.hasText("first") }
                assertFalse(scene.hasText("archived"))
                scene.clickDescription("Show archived")
                scene.settle { scene.hasText("archived") }
                scene.clickDescription("Actions for session 7")
                scene.settle { scene.hasText("Index: 7") && scene.hasText("Archive") }
                assertTrue(scene.nodes().any {
                    it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.startsWith("Created at:") } == true
                })
                assertFalse(scene.nodes().any {
                    it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.startsWith("Updated at:") } == true
                })
                scene.click("Fork")
                scene.settle { ports.calls.contains("fork:7") && scene.hasText("first") && !scene.hasText("Index: 7") }
                assertTrue(ports.opened.isEmpty())
                assertEquals(0, ports.dismissals)
                scene.clickDescription("Actions for session 8")
                scene.settle { scene.hasText("Unarchive") }
                scene.click("Unarchive")
                scene.settle { ports.calls.contains("unarchive:8") && !scene.hasText("Unarchive") }
                ports.openGate = CompletableDeferred()
                scene.click("first")
                scene.settle { ports.opened == listOf(7) }
                assertEquals(0, ports.dismissals)
                ports.openGate!!.complete(Unit)
                scene.settle { scene.hasText("closed") }
                assertEquals(1, ports.dismissals)
                assertEquals(listOf("load:false", "load:true", "fork:7", "load:true", "unarchive:8", "load:true"), ports.calls)
            } finally { scene.close() }
        }
    } }

    test("delayed old menu actions cannot clear a newer menu or act after the captured row changed") { offscreenTest {
        val ports = CatalogPorts().apply { entries = entries.map { it.copy(archived = false) } }
        withCatalogFixture(ports) { vm ->
            var captured: CapturedCatalogMenu? = null
            val scene = OffscreenScene.create {
                val graphical = rememberComposeSessionCatalogRenderer()
                val renderer = object : SessionCatalogRenderer by graphical {
                    @Composable
                    override fun ContextMenu(
                        entry: SessionCatalogEntry, origin: SessionCatalogMenuOrigin, onDismiss: () -> Unit,
                        onFork: () -> Unit, onArchive: () -> Unit, onUnarchive: () -> Unit, onDelete: () -> Unit,
                    ) {
                        captured = CapturedCatalogMenu(onDismiss, onFork, onArchive, onDelete)
                        graphical.ContextMenu(entry, origin, onDismiss, onFork, onArchive, onUnarchive, onDelete)
                    }
                }
                SessionCatalogContent(vm, renderer, { throw it })
            }
            try {
                scene.settle { scene.hasText("first") && scene.hasText("archived") }
                scene.clickDescription("Actions for session 7")
                scene.settle { scene.hasText("Index: 7") }
                val old = assertNotNull(captured)
                scene.escape()
                scene.settle { !scene.hasText("Index: 7") }
                scene.clickDescription("Actions for session 8")
                scene.settle { scene.hasText("Index: 8") }
                val next = assertNotNull(captured)
                old.dismiss(); old.fork(); old.archive(); old.delete()
                scene.settle { scene.hasText("Index: 8") }
                assertEquals(listOf("load:false"), ports.calls)
                assertNull(vm.deleteTarget.value)
                ports.entries = ports.entries.map { if (it.sessionIndex == 8) it.copy(threadName = "replacement") else it }
                vm.refresh()
                scene.settle { scene.hasText("replacement") && !scene.hasText("Index: 8") }
                next.fork(); next.archive(); next.delete()
                scene.settle { scene.hasText("replacement") }
                assertEquals(listOf("load:false", "load:false"), ports.calls)
                assertNull(vm.deleteTarget.value)
            } finally { scene.close() }
        }
    } }

    for (deleted in listOf(false, true)) {
        test("real Delete modal keeps the exact child and never substitutes its target result=$deleted") { offscreenTest {
            val ports = CatalogPorts().apply { deleteResult = deleted }
            withCatalogFixture(ports) { vm ->
                val visible = mutableStateOf(true)
                val scene = OffscreenScene.create {
                    if (visible.value) SessionCatalogContent(vm, rememberComposeSessionCatalogRenderer(), { throw it }) else UiText("closed")
                }
                try {
                    scene.settle { scene.hasText("first") }
                    scene.clickDescription("Actions for session 7")
                    scene.settle { scene.hasText("Delete") && scene.hasText("Index: 7") }
                    scene.click("Delete")
                    scene.settle { scene.hasText("Delete first?") }
                    val handle = assertNotNull(vm.deleteTarget.value)
                    scene.click("Delete")
                    scene.settle { ports.calls.contains("delete:7") && vm.state.value is SessionCatalogState.Loaded }
                    if (deleted) {
                        scene.settle { vm.deleteTarget.value == null && scene.hasText("No persisted sessions") }
                    } else {
                        assertSame(handle, vm.deleteTarget.value)
                        assertTrue(handle.viewModel.isActive)
                        scene.click("Cancel")
                        scene.settle { vm.deleteTarget.value == null && !scene.hasText("Delete first?") }
                    }
                    assertFalse(handle.viewModel.isActive)
                    assertEquals(listOf("load:false", "delete:7", "load:false"), ports.calls)
                    assertTrue(ports.opened.isEmpty())
                    assertEquals(0, ports.dismissals)
                    if (!deleted) {
                        val next = assertNotNull(vm.requestDelete(vm.state.value.sessions.first()))
                        scene.settle { scene.hasText("Delete first?") }
                        vm.dismissDelete(handle)
                        assertSame(next, vm.deleteTarget.value)
                        visible.value = false
                        scene.settle { scene.hasText("closed") && vm.deleteTarget.value == null }
                        assertFalse(next.viewModel.isActive)
                    }
                } finally { scene.close() }
                vm.refresh()
                assertEquals("load:false", ports.calls.last())
            }
        } }
    }

    for (fork in listOf(false, true)) {
        test("unmount cancels only the renderer caller without replaying or closing the catalog fork=$fork") { offscreenTest {
            val ports = CatalogPorts().apply { if (fork) forkGate = CompletableDeferred() else openGate = CompletableDeferred() }
            withCatalogFixture(ports) { vm ->
                val visible = mutableStateOf(true)
                val failures = mutableListOf<Throwable>()
                val scene = OffscreenScene.create {
                    if (visible.value) SessionCatalogContent(vm, rememberComposeSessionCatalogRenderer(), failures::add) else UiText("closed")
                }
                try {
                    scene.settle { scene.hasText("first") }
                    if (fork) {
                        scene.clickDescription("Actions for session 7")
                        scene.settle { scene.hasText("Fork") }
                        scene.click("Fork")
                    } else scene.click("first")
                    scene.settle { if (fork) ports.calls.contains("fork:7") else ports.opened == listOf(7) }
                    visible.value = false
                    scene.settle { scene.hasText("closed") && ports.cancellations == 1 }
                    ports.forkGate?.complete(Unit); ports.openGate?.complete(Unit)
                    vm.refresh()
                    assertTrue(failures.isEmpty())
                    assertEquals(0, ports.dismissals)
                    assertEquals(if (fork) 1 else 0, ports.calls.count { it == "fork:7" })
                    assertEquals(if (fork) emptyList() else listOf(7), ports.opened)
                } finally { scene.close() }
            }
        } }
    }

    test("ordinary open failure is reported once and leaves the loaded popup intact") { offscreenTest {
        val failure = IllegalStateException("registry failure")
        val ports = CatalogPorts().apply { openFailure = failure }
        withCatalogFixture(ports) { vm ->
            val failures = mutableListOf<Throwable>()
            val scene = OffscreenScene.create { SessionCatalogContent(vm, rememberComposeSessionCatalogRenderer(), failures::add) }
            try {
                scene.settle { scene.hasText("first") }
                scene.click("first")
                scene.settle { failures.size == 1 }
                assertSame(failure, generateSequence(failures.single()) { it.cause }.last())
                assertEquals(0, ports.dismissals)
                assertTrue(scene.hasText("first"))
                assertFalse(scene.hasText("No persisted sessions"))
            } finally { scene.close() }
        }
    } }
}

private data class CapturedCatalogMenu(
    val dismiss: () -> Unit, val fork: () -> Unit, val archive: () -> Unit, val delete: () -> Unit,
)

private suspend fun withCatalogFixture(ports: CatalogPorts, block: suspend (SessionCatalogViewModel) -> Unit) {
    val owner = CoroutineScope(currentCoroutineContext() + Job(currentCoroutineContext()[Job]))
    val vm = DefaultSessionCatalogViewModel(owner, ports, ports)
    try { block(vm) } finally { vm.close(); owner.coroutineContext.job.cancelAndJoin() }
}

private fun OffscreenScene.catalogRows(): Int = nodes().flatMap {
    it.config.getOrNull(SemanticsProperties.Text).orEmpty()
}.count { it.text.startsWith("row") }

private class CatalogPorts : SessionCatalogDependencies, SessionCatalogInteractions {
    var entries = listOf(
        SessionCatalogEntry(7, "first", createdAt = Instant.parse("2026-10-20T01:02:03Z")),
        SessionCatalogEntry(8, "archived", archived = true),
    )
    val calls = mutableListOf<String>()
    val opened = mutableListOf<Int>()
    var dismissals = 0
    var cancellations = 0
    var onDismiss: () -> Unit = {}
    var loadGate: CompletableDeferred<Unit>? = null
    var forkGate: CompletableDeferred<Unit>? = null
    var openGate: CompletableDeferred<Unit>? = null
    var openFailure: Throwable? = null
    var deleteResult = true
    override suspend fun load(showArchived: Boolean): List<SessionCatalogEntry> {
        calls += "load:$showArchived"
        loadGate?.await()
        return entries.filter { showArchived || !it.archived }
    }
    override suspend fun archive(sessionIndex: Int) {
        calls += "archive:$sessionIndex"
        entries = entries.map { if (it.sessionIndex == sessionIndex) it.copy(archived = true) else it }
    }
    override suspend fun unarchive(sessionIndex: Int) {
        calls += "unarchive:$sessionIndex"
        entries = entries.map { if (it.sessionIndex == sessionIndex) it.copy(archived = false) else it }
    }
    override suspend fun fork(sessionIndex: Int): Int {
        calls += "fork:$sessionIndex"
        try { forkGate?.await() } catch (failure: CancellationException) { cancellations++; throw failure }
        return 99
    }
    override suspend fun delete(sessionIndex: Int): Boolean {
        calls += "delete:$sessionIndex"
        if (deleteResult) entries = entries.filterNot { it.sessionIndex == sessionIndex }
        return deleteResult
    }
    override suspend fun openSession(sessionIndex: Int) {
        opened += sessionIndex
        openFailure?.let { throw it }
        try { openGate?.await() } catch (failure: CancellationException) { cancellations++; throw failure }
    }
    override fun dismissPopup() { dismissals++; onDismiss() }
}
