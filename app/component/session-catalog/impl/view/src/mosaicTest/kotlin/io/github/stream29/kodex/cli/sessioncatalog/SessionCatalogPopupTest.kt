@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.cli.sessioncatalog

import io.github.stream29.kodex.cli.components.rememberRunningIndicatorFrame
import io.github.stream29.kodex.cli.components.RunningIndicatorFrames
import io.github.stream29.kodex.cli.components.RunningIndicatorFrameDurationMillis

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Text
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.sessioncatalog.DefaultSessionCatalogViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogDependencies
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogInteractions
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogState
import io.github.stream29.kodex.cli.components.TuiPopupHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlin.time.Duration.Companion.milliseconds
import kotlin.test.*
import kotlin.time.Instant

val sessionCatalogPopupTest by testSuite {
    test("mount reads exactly once, distinguishes progress from loaded empty, and recompose does not read") {
        val ports = RenderCatalogPorts().apply { loadGate = CompletableDeferred() }
        withCatalog(ports) { vm ->
            var redraw by mutableStateOf(0)
            runMosaicTest {
                assertEquals(SessionCatalogState.Unloaded, vm.state.value)
                assertTrue(ports.calls.isEmpty())
                val first = setContentAndSnapshot {
                    TuiPopupHost(modifier = Modifier.width(90).height(28)) {
                        Text("draw=$redraw")
                        SessionCatalogPopup(vm)
                    }
                }
                assertTrue("Loading sessions…" in first, first)
                assertFalse("No persisted sessions" in first, first)
                catalogSnapshot("Loading sessions…")
                assertEquals(listOf("load:false"), ports.calls)
                redraw++
                catalogSnapshot("draw=1")
                assertEquals(listOf("load:false"), ports.calls)
                ports.loadGate!!.complete(emptyList())
                val empty = catalogSnapshot("No persisted sessions")
                assertFalse("Loading sessions…" in empty, empty)
                assertEquals(listOf("load:false"), ports.calls)
            }
        }
    }

    test("full popup preserves row order, archive checkbox and fork-only navigation semantics") {
        val ports = RenderCatalogPorts()
        withCatalog(ports) { vm ->
            runMosaicTest {
                setContentAndSnapshot {
                    TuiPopupHost(modifier = Modifier.width(90).height(28)) { SessionCatalogPopup(vm) }
                }
                val rows = catalogSnapshot("[first]")
                assertTrue(rows.indexOf("[first]") < rows.indexOf("[second]"), rows)
                assertFalse("[archived]" in rows, rows)
                clickCatalogText(rows, "Show archived")
                val included = catalogSnapshot("[archived]")
                assertTrue("[x] Show archived" in included, included)
                assertEquals(listOf("load:false", "load:true"), ports.calls)
                secondaryCatalogText(included, "[first]")
                val menu = catalogSnapshot("[Archive]")
                assertTrue("Index: 7" in menu, menu)
                assertTrue("Created at:" in menu, menu)
                assertFalse("Updated at:" in menu, menu)
                sendKeyEvent(KeyboardEvent(KeyboardEvent.Down))
                catalogSnapshot("")
                sendKeyEvent(KeyboardEvent(KeyboardEvent.Down))
                catalogSnapshot("")
                sendKeyEvent(KeyboardEvent(13))
                awaitCatalogCondition { ports.calls == listOf("load:false", "load:true", "fork:7", "load:true") }
                catalogSnapshot("[first]")
                assertEquals(listOf("load:false", "load:true", "fork:7", "load:true"), ports.calls)
                assertEquals(emptyList(), ports.opened)
                assertEquals(emptyList(), ports.dismissed)
            }
        }
    }

    test("primary row open completes before the exact popup is dismissed") {
        val ports = RenderCatalogPorts().apply { openGate = CompletableDeferred() }
        withCatalog(ports) { vm ->
            var visible by mutableStateOf(true)
            var marker by mutableStateOf("waiting")
            ports.onDismiss = { visible = false; marker = "dismissed" }
            runMosaicTest {
                setContentAndSnapshot {
                    TuiPopupHost(modifier = Modifier.width(90).height(28)) {
                        Text(marker)
                        if (visible) SessionCatalogPopup(vm)
                    }
                }
                clickCatalogText(catalogSnapshot("[first]"), "[first]")
                awaitCatalogCondition { ports.opened == listOf(7) }
                assertEquals(listOf(7), ports.opened)
                assertEquals(emptyList(), ports.dismissed)
                ports.openGate!!.complete(Unit)
                catalogSnapshot("dismissed")
                assertEquals(listOf("catalog"), ports.dismissed)
                assertEquals(listOf("load:false"), ports.calls)
            }
        }
    }

    test("failed row open leaves the popup and does not synthesize empty or dismiss") {
        val ports = RenderCatalogPorts().apply { openFailure = IllegalStateException("registry") }
        withCatalog(ports) { vm ->
            runMosaicTest {
                setContentAndSnapshot {
                    TuiPopupHost(modifier = Modifier.width(90).height(28)) { SessionCatalogPopup(vm) }
                }
                clickCatalogText(catalogSnapshot("[first]"), "[first]")
                val remaining = catalogSnapshot("[first]")
                assertFalse("No persisted sessions" in remaining, remaining)
                assertEquals(listOf(7), ports.opened)
                assertEquals(emptyList(), ports.dismissed)
            }
        }
    }

    for (deleted in listOf(false, true)) {
        test("full renderer owns exact Delete child: result $deleted and stale dismissal") {
            val ports = RenderCatalogPorts().apply { deleteResult = deleted }
            withCatalog(ports) { vm ->
                runMosaicTest {
                    setContentAndSnapshot {
                        TuiPopupHost(modifier = Modifier.width(90).height(28)) { SessionCatalogPopup(vm) }
                    }
                    secondaryCatalogText(catalogSnapshot("[first]"), "[first]")
                    catalogSnapshot("[Archive]")
                    sendKeyEvent(KeyboardEvent(KeyboardEvent.Down))
                    catalogSnapshot("")
                    sendKeyEvent(KeyboardEvent(13))
                    val confirmation = catalogSnapshot("Delete first?")
                    assertTrue("[Cancel]" in confirmation, confirmation)
                    val old = vm.deleteTarget.value!!
                    assertEquals(7, old.viewModel.sessionIndex)
                    clickCatalogText(confirmation, "[Delete]")
                    awaitCatalogCondition { ports.calls == listOf("load:false", "delete:7", "load:false") }
                    if (deleted) {
                        awaitCatalogCondition { vm.deleteTarget.value == null }
                        catalogSnapshot("[second]")
                        assertNull(vm.deleteTarget.value)
                        assertFalse(old.viewModel.isActive)
                    } else {
                        catalogSnapshot("Delete first?")
                        assertSame(old, vm.deleteTarget.value)
                        assertTrue(old.viewModel.isActive)
                        // Parent supplies a replacement business child; delayed old callbacks
                        // and the old renderer's disposal may not clear the replacement.
                        val next = vm.requestDelete(vm.state.value.sessions.last())!!
                        catalogSnapshot("Delete second?")
                        assertFalse(old.viewModel.isActive)
                        vm.dismissDelete(old)
                        assertSame(next, vm.deleteTarget.value)
                        sendKeyEvent(KeyboardEvent(27))
                        catalogSnapshot("[first]")
                        assertNull(vm.deleteTarget.value)
                        assertFalse(next.viewModel.isActive)
                    }
                    assertEquals(listOf("load:false", "delete:7", "load:false"), ports.calls)
                    assertTrue(ports.opened.isEmpty())
                    assertTrue(ports.dismissed.isEmpty())
                }
            }
        }
    }

    test("successful delete followed by reload failure renders the same child and old snapshot") {
        val ports = RenderCatalogPorts()
        withCatalog(ports) { vm ->
            runMosaicTest {
                setContentAndSnapshot {
                    TuiPopupHost(modifier = Modifier.width(90).height(28)) { SessionCatalogPopup(vm) }
                }
                catalogSnapshot("[first]")
                val previous = vm.state.value
                val handle = vm.requestDelete(previous.sessions.first())!!
                catalogSnapshot("Delete first?")
                val failure = IllegalStateException("reload after mutation")
                ports.loadFailure = failure
                // Exercise the exact child rendered here, with its real exception exposed
                // to the caller instead of uncaught renderer-scope exception handling.
                assertSame(failure, generateSequence<Throwable>(
                    assertFailsWith<IllegalStateException> { handle.viewModel.delete() },
                ) { it.cause }.last())
                assertSame(previous, vm.state.value)
                assertSame(handle, vm.deleteTarget.value)
                val remaining = catalogSnapshot("Delete first?")
                assertFalse("No persisted sessions" in remaining, remaining)
                assertEquals(listOf("load:false", "delete:7", "load:false"), ports.calls)
                ports.loadFailure = null
                vm.dismissDelete(handle)
                catalogSnapshot("[first]")
            }
        }
    }

    test("Cancel confirmation does no backend I/O and renderer unmount leaves catalog owner usable") {
        val ports = RenderCatalogPorts()
        withCatalog(ports) { vm ->
            var visible by mutableStateOf(true)
            runMosaicTest {
                setContentAndSnapshot {
                    TuiPopupHost(modifier = Modifier.width(90).height(28)) {
                        Text(if (visible) "mounted" else "unmounted")
                        if (visible) SessionCatalogPopup(vm)
                    }
                }
                catalogSnapshot("[first]")
                val row = vm.state.value.sessions.first()
                val handle = vm.requestDelete(row)!!
                catalogSnapshot("Delete first?")
                sendKeyEvent(KeyboardEvent(13)) // Cancel is initially focused.
                catalogSnapshot("[first]")
                assertNull(vm.deleteTarget.value)
                assertFalse(handle.viewModel.isActive)
                val next = vm.requestDelete(row)!!
                catalogSnapshot("Delete first?")
                visible = false
                catalogSnapshot("unmounted")
                assertNull(vm.deleteTarget.value)
                assertFalse(next.viewModel.isActive)
                assertEquals(listOf("load:false"), ports.calls)
                vm.refresh() // View unmount is not owner/backend close.
                assertEquals(listOf("load:false", "load:false"), ports.calls)
            }
        }
    }

    test("unmount cancels pending renderer refresh without fabricating Loaded empty") {
        val ports = RenderCatalogPorts().apply { loadGate = CompletableDeferred() }
        withCatalog(ports) { vm ->
            var visible by mutableStateOf(true)
            runMosaicTest {
                setContentAndSnapshot {
                    TuiPopupHost(modifier = Modifier.width(90).height(28)) {
                        Text(if (visible) "mounted" else "unmounted")
                        if (visible) SessionCatalogPopup(vm)
                    }
                }
                catalogSnapshot("Loading sessions…")
                visible = false
                catalogSnapshot("unmounted")
                assertEquals(SessionCatalogState.Unloaded, vm.state.value)
                ports.loadGate = null
                vm.refresh()
                assertIs<SessionCatalogState.Loaded>(vm.state.value)
                assertEquals(listOf("load:false", "load:false"), ports.calls)
            }
        }
    }

    test("unmount cancels pending child caller, closes that handle and never replays deletion") {
        val ports = RenderCatalogPorts().apply { deleteGate = CompletableDeferred() }
        withCatalog(ports) { vm ->
            var visible by mutableStateOf(true)
            runMosaicTest {
                setContentAndSnapshot {
                    TuiPopupHost(modifier = Modifier.width(90).height(28)) {
                        Text(if (visible) "mounted" else "unmounted")
                        if (visible) SessionCatalogPopup(vm)
                    }
                }
                catalogSnapshot("[first]")
                val handle = vm.requestDelete(vm.state.value.sessions.first())!!
                clickCatalogText(catalogSnapshot("Delete first?"), "[Delete]")
                awaitCatalogCondition { ports.calls == listOf("load:false", "delete:7") }
                assertEquals(listOf("load:false", "delete:7"), ports.calls)
                visible = false
                catalogSnapshot("unmounted")
                assertNull(vm.deleteTarget.value)
                assertFalse(handle.viewModel.isActive)
                ports.deleteGate!!.complete(Unit)
                ports.deleteGate = null
                vm.refresh()
                assertEquals(listOf("load:false", "delete:7", "load:false"), ports.calls)
                assertTrue(ports.dismissed.isEmpty())
            }
        }
    }

    test("replacing the renderer cancels old row callers and refreshes the new VM once") {
        val oldPorts = RenderCatalogPorts().apply { openGate = CompletableDeferred() }
        val nextPorts = RenderCatalogPorts().apply { entries = listOf(SessionCatalogEntry(42, "next popup")) }
        withCatalog(oldPorts) { old ->
            withCatalog(nextPorts) { next ->
                var current by mutableStateOf(old)
                runMosaicTest {
                    setContentAndSnapshot {
                        TuiPopupHost(modifier = Modifier.width(90).height(28)) { SessionCatalogPopup(current) }
                    }
                    clickCatalogText(catalogSnapshot("[first]"), "[first]")
                    awaitCatalogCondition { oldPorts.opened == listOf(7) }
                    assertEquals(listOf(7), oldPorts.opened)
                    current = next
                    catalogSnapshot("[next popup]")
                    oldPorts.openGate!!.complete(Unit)
                    assertTrue(oldPorts.dismissed.isEmpty())
                    assertTrue(nextPorts.dismissed.isEmpty())
                    assertEquals(listOf("load:false"), nextPorts.calls)
                    assertTrue(nextPorts.opened.isEmpty())
                }
            }
        }
    }

    test("running animation in the full popup uses backend running, not owner residency") {
        val ports = RenderCatalogPorts().apply {
            entries = listOf(
                SessionCatalogEntry(7, "running", running = true),
                SessionCatalogEntry(2, "idle", isActive = true),
            )
        }
        withCatalog(ports) { vm ->
            runMosaicTest {
                setContentAndSnapshot {
                    TuiPopupHost(modifier = Modifier.width(90).height(28)) { SessionCatalogPopup(vm) }
                }
                val first = catalogSnapshot("running]")
                assertTrue("[idle]" in first, first)
                assertTrue(RunningIndicatorFrames.any { "[${it}running]" in first }, first)
                var changed = first
                repeat(20) { if (changed == first) changed = awaitSnapshot() }
                assertNotEquals(first, changed)
                assertTrue("[idle]" in changed, changed)
                assertEquals(listOf("load:false"), ports.calls)
            }
        }
    }

    test("close and delayed navigation dismiss only the captured popup, never a replacement") {
        val ports = RenderCatalogPorts().apply { openGate = CompletableDeferred() }
        withCatalog(ports) { vm ->
            runMosaicTest {
                setContentAndSnapshot {
                    TuiPopupHost(modifier = Modifier.width(90).height(28)) { SessionCatalogPopup(vm) }
                }
                clickCatalogText(catalogSnapshot("[first]"), "[first]")
                ports.currentPopup = "replacement"
                ports.openGate!!.complete(Unit)
                catalogSnapshot("[first]")
                assertTrue(ports.dismissed.isEmpty())
                clickCatalogText(catalogSnapshot("[Close]"), "[Close]")
                assertTrue(ports.dismissed.isEmpty())
                assertEquals("replacement", ports.currentPopup)
                assertEquals(listOf(7), ports.opened)
            }
        }
    }
}

private suspend fun withCatalog(
    ports: RenderCatalogPorts,
    action: suspend (DefaultSessionCatalogViewModel) -> Unit,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val vm = DefaultSessionCatalogViewModel(scope, ports, ports)
    try {
        action(vm)
    } finally {
        vm.close()
        scope.cancel()
    }
}

private suspend fun TestMosaic<String>.catalogSnapshot(expected: String): String {
    var latest = ""
    repeat(5) {
        latest = try {
            awaitSnapshot()
        } catch (_: TimeoutCancellationException) {
            draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
        }
        // Popup items align to the widest (timestamp) row. Padding is not part of
        // the semantic label, but coordinate click helpers still use unpadded buttons.
        latest = latest.replace(Regex(" +]"), "]")
        if (expected in latest) return latest
    }
    assertTrue(expected in latest, latest)
    return latest
}

private suspend fun TestMosaic<String>.awaitCatalogCondition(condition: () -> Boolean) {
    repeat(100) {
        if (condition()) return
        try { awaitSnapshot(50.milliseconds) } catch (_: TimeoutCancellationException) { }
    }
    assertTrue(condition(), draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false))
}

private suspend fun TestMosaic<String>.clickCatalogText(snapshot: String, text: String) {
    val lines = snapshot.lines()
    val row = lines.indexOfFirst { text in it }
    assertTrue(row >= 0, snapshot)
    val column = lines[row].indexOf(text) + 1
    sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Press, MouseEvent.Button.Left))
    catalogSnapshot("")
    sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Release))
    catalogSnapshot("")
}

private suspend fun TestMosaic<String>.secondaryCatalogText(snapshot: String, text: String) {
    val lines = snapshot.lines()
    val row = lines.indexOfFirst { text in it }
    assertTrue(row >= 0, snapshot)
    val column = lines[row].indexOf(text) + 1
    sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Press, MouseEvent.Button.Right))
    sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Release))
}

private class RenderCatalogPorts : SessionCatalogDependencies, SessionCatalogInteractions {
    val calls = mutableListOf<String>()
    val opened = mutableListOf<Int>()
    val dismissed = mutableListOf<String>()
    var currentPopup: String? = "catalog"
    var onDismiss: () -> Unit = {}
    var entries = listOf(
        SessionCatalogEntry(7, "first", createdAt = Instant.parse("2026-01-01T00:00:00Z")),
        SessionCatalogEntry(2, "second"),
        SessionCatalogEntry(4, "archived", archived = true),
    )
    var deleteResult = true
    var loadGate: CompletableDeferred<List<SessionCatalogEntry>>? = null
    var loadFailure: Exception? = null
    var openGate: CompletableDeferred<Unit>? = null
    var deleteGate: CompletableDeferred<Unit>? = null
    var openFailure: Exception? = null
    override suspend fun load(showArchived: Boolean): List<SessionCatalogEntry> {
        calls += "load:$showArchived"
        loadFailure?.let { throw it }
        return loadGate?.await() ?: entries.filter { showArchived || !it.archived }
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
        return 42
    }
    override suspend fun delete(sessionIndex: Int): Boolean {
        calls += "delete:$sessionIndex"
        deleteGate?.await()
        if (deleteResult) entries = entries.filterNot { it.sessionIndex == sessionIndex }
        return deleteResult
    }
    override suspend fun openSession(sessionIndex: Int) {
        opened += sessionIndex
        openFailure?.let { throw it }
        openGate?.await()
    }
    override fun dismissPopup() {
        if (currentPopup != "catalog") return
        dismissed += "catalog"
        currentPopup = null
        onDismiss()
    }
}
