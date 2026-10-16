@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.app.sessioncatalog

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogDependencies
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogInteractions
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

private fun Throwable.originalFailure(): Throwable = generateSequence(this) { it.cause }.last()

val sessionCatalogInteractionTest by testSuite {
    test("open captures the row and dismisses the exact opening only after success") {
        runTest {
            val backend = InteractionBackend()
            val host = InteractionHost()
            val catalog = DefaultSessionCatalogViewModel(this, backend, host)
            try {
                assertEquals(emptyList(), backend.calls)
                assertEquals(SessionCatalogState.Unloaded, catalog.state.value)
                assertNull(catalog.deleteTarget.value)
                catalog.refresh()
                val captured = catalog.state.value.sessions.first()
                host.openGate = CompletableDeferred()
                val opening = async { catalog.requestOpen(captured) }
                runCurrent()
                assertEquals(listOf("open:7"), host.calls)
                assertEquals(emptyList(), host.dismissed)
                // A host popup replacement while navigation waits must not be dismissed.
                host.currentPopup = "replacement"
                host.openGate!!.complete(Unit)
                opening.await()
                assertEquals(listOf("open:7", "dismiss:catalog"), host.calls)
                assertEquals(emptyList(), host.dismissed)
                assertEquals("replacement", host.currentPopup)
                assertEquals(listOf("load:false"), backend.calls)
            } finally {
                catalog.close()
            }
        }
    }

    test("open failure is the real exception and never dismisses or reads again") {
        runTest {
            val backend = InteractionBackend()
            val host = InteractionHost()
            val catalog = DefaultSessionCatalogViewModel(this, backend, host)
            try {
                catalog.refresh()
                val snapshot = catalog.state.value
                val failure = IllegalArgumentException("registry failure")
                host.openFailure = failure
                assertSame(failure, assertFailsWith<IllegalArgumentException> {
                    catalog.requestOpen(snapshot.sessions.first())
                }.originalFailure())
                assertSame(snapshot, catalog.state.value)
                assertEquals(listOf("open:7"), host.calls)
                assertEquals(listOf("load:false"), backend.calls)
            } finally {
                catalog.close()
            }
        }
    }

    test("stale captured rows and callbacks cannot redirect to a refreshed row or child") {
        runTest {
            val backend = InteractionBackend()
            val host = InteractionHost()
            val catalog = DefaultSessionCatalogViewModel(this, backend, host)
            try {
                catalog.refresh()
                val oldRow = catalog.state.value.sessions.first()
                val old = catalog.requestDelete(oldRow)!!
                backend.entries = backend.entries.map { it.copy(threadName = "replacement row") }
                catalog.refresh()
                catalog.requestOpen(oldRow)
                assertNull(catalog.requestDelete(oldRow))
                assertEquals(emptyList(), host.calls)
                val next = catalog.requestDelete(catalog.state.value.sessions.last())!!
                assertFalse(old.viewModel.isActive)
                catalog.dismissDelete(old)
                assertSame(next, catalog.deleteTarget.value)
                assertFailsWith<IllegalStateException> { old.viewModel.delete() }
                assertFalse(backend.calls.any { it.startsWith("delete") })
                catalog.dismissDelete(next)
                assertNull(catalog.deleteTarget.value)
                assertFalse(next.viewModel.isActive)
            } finally {
                catalog.close()
            }
        }
    }

    test("equal refreshed snapshots keep captured rendered rows usable under StateFlow conflation") {
        runTest {
            val backend = InteractionBackend()
            val host = InteractionHost()
            val catalog = DefaultSessionCatalogViewModel(this, backend, host)
            try {
                catalog.refresh()
                val captured = catalog.state.value.sessions.first()
                backend.entries = backend.entries.map { it.copy() }
                catalog.refresh()
                assertNotSame(captured, catalog.state.value.sessions.first())
                val handle = catalog.requestDelete(captured)!!
                assertSame(captured, handle.target)
                catalog.dismissDelete(handle)
                catalog.requestOpen(captured)
                assertEquals(listOf("open:7", "dismiss:catalog"), host.calls)
            } finally {
                catalog.close()
            }
        }
    }

    for (deleted in listOf(false, true)) {
        test("delete $deleted reloads once and only true closes the exact child") {
            runTest {
                val backend = InteractionBackend().apply { deleteResult = deleted }
                val catalog = DefaultSessionCatalogViewModel(this, backend)
                try {
                    catalog.refresh()
                    val target = catalog.state.value.sessions.first()
                    val handle = catalog.requestDelete(target)!!
                    assertSame(target, handle.target)
                    assertEquals(7, handle.viewModel.sessionIndex)
                    assertEquals("first", handle.viewModel.threadName)
                    assertEquals(listOf("load:false"), backend.calls)
                    assertEquals(deleted, handle.viewModel.delete())
                    assertEquals(listOf("load:false", "delete:7", "load:false"), backend.calls)
                    if (deleted) {
                        assertNull(catalog.deleteTarget.value)
                        assertFalse(handle.viewModel.isActive)
                    } else {
                        assertSame(handle, catalog.deleteTarget.value)
                        assertTrue(handle.viewModel.isActive)
                    }
                } finally {
                    catalog.close()
                }
            }
        }
    }

    test("successful delete followed by failed reload preserves snapshot and child without replay") {
        runTest {
            val backend = InteractionBackend()
            val catalog = DefaultSessionCatalogViewModel(this, backend)
            try {
                catalog.refresh()
                val previous = catalog.state.value
                val handle = catalog.requestDelete(previous.sessions.first())!!
                val failure = IllegalStateException("reload failed after deletion")
                backend.loadFailure = failure
                assertSame(failure, assertFailsWith<IllegalStateException> { handle.viewModel.delete() }.originalFailure())
                assertSame(previous, catalog.state.value)
                assertSame(handle, catalog.deleteTarget.value)
                assertTrue(handle.viewModel.isActive)
                assertEquals(listOf(7), backend.deleted)
                assertEquals(listOf("load:false", "delete:7", "load:false"), backend.calls)
                backend.loadFailure = null
                catalog.refresh()
                assertEquals(listOf(2), catalog.state.value.sessions.map { it.sessionIndex })
                assertEquals(listOf(7), backend.deleted)
            } finally {
                catalog.close()
            }
        }
    }

    test("cancelling reload after accepted deletion restores only the frontend snapshot, not backend data") {
        runTest {
            val backend = InteractionBackend()
            val catalog = DefaultSessionCatalogViewModel(this, backend)
            try {
                catalog.refresh()
                val previous = catalog.state.value
                val handle = catalog.requestDelete(previous.sessions.first())!!
                backend.loadGate = CompletableDeferred()
                val deletion = async { handle.viewModel.delete() }
                runCurrent()
                assertEquals(listOf(7), backend.deleted)
                assertIs<SessionCatalogState.Loading>(catalog.state.value)
                deletion.cancelAndJoin()
                assertSame(previous, catalog.state.value)
                assertSame(handle, catalog.deleteTarget.value)
                assertTrue(handle.viewModel.isActive)
                backend.loadGate = null
                catalog.refresh()
                assertEquals(listOf(2), catalog.state.value.sessions.map { it.sessionIndex })
                assertEquals(listOf(7), backend.deleted)
                assertEquals(listOf("load:false", "delete:7", "load:false", "load:false"), backend.calls)
            } finally {
                catalog.close()
            }
        }
    }

    for (command in listOf("archive", "unarchive", "fork", "delete")) {
        test("$command success followed by reload failure throws the original exception without replay") {
            runTest {
                val backend = InteractionBackend().apply {
                    entries = entries.map { it.copy(archived = command == "unarchive") }
                }
                val catalog = DefaultSessionCatalogViewModel(this, backend)
                try {
                    catalog.setShowArchived(true)
                    val previous = catalog.state.value
                    val failure = IllegalArgumentException("$command reload")
                    backend.loadFailure = failure
                    assertSame(failure, assertFailsWith<IllegalArgumentException> {
                        when (command) {
                            "archive" -> catalog.archive(7)
                            "unarchive" -> catalog.unarchive(7)
                            "fork" -> catalog.fork(7)
                            "delete" -> catalog.delete(7)
                            else -> error("Unknown test command: $command")
                        }
                    }.originalFailure())
                    assertSame(previous, catalog.state.value)
                    assertEquals(listOf("load:true", "$command:7", "load:true"), backend.calls)
                } finally {
                    catalog.close()
                }
            }
        }
    }

    test("initial read failure remains Unloaded and data-only navigation has explicit missing-port errors") {
        runTest {
            val backend = InteractionBackend()
            val catalog = DefaultSessionCatalogViewModel(this, backend)
            try {
                val failure = IllegalStateException("initial catalog failure")
                backend.loadFailure = failure
                assertSame(failure, assertFailsWith<IllegalStateException> { catalog.refresh() }.originalFailure())
                assertEquals(SessionCatalogState.Unloaded, catalog.state.value)
                backend.loadFailure = null
                catalog.refresh()
                assertFailsWith<IllegalStateException> { catalog.requestOpen(catalog.state.value.sessions.first()) }
                assertFailsWith<IllegalStateException> { catalog.dismiss() }
                assertEquals(listOf("load:false", "load:false"), backend.calls)
            } finally {
                catalog.close()
            }
        }
    }

    test("an accepted old deletion cannot dismiss a replacement confirmation") {
        runTest {
            val backend = InteractionBackend().apply { deleteGate = CompletableDeferred() }
            val catalog = DefaultSessionCatalogViewModel(this, backend)
            try {
                catalog.refresh()
                val rows = catalog.state.value.sessions
                val old = catalog.requestDelete(rows.first())!!
                val deletion = async { old.viewModel.delete() }
                runCurrent()
                val next = catalog.requestDelete(rows.last())!!
                assertFalse(old.viewModel.isActive)
                backend.deleteGate!!.complete(Unit)
                assertTrue(deletion.await())
                assertSame(next, catalog.deleteTarget.value)
                assertTrue(next.viewModel.isActive)
                catalog.dismissDelete(old) // delayed dismissal callback from the old renderer
                assertSame(next, catalog.deleteTarget.value)
                assertEquals(listOf(7), backend.deleted)
            } finally {
                catalog.close()
            }
        }
    }

    test("queued old child deletion is rejected after replacing its confirmation") {
        runTest {
            val backend = InteractionBackend()
            val hostGate = CompletableDeferred<Unit>()
            val host = InteractionHost().apply { openGate = hostGate }
            val catalog = DefaultSessionCatalogViewModel(this, backend, host)
            try {
                catalog.refresh()
                val row = catalog.state.value.sessions.first()
                val confirmation = catalog.requestDelete(row)!!
                // Hold the serialized command without changing the loaded catalog.
                val opening = async { catalog.requestOpen(row) }
                runCurrent()
                // Capture the expected failure inside the child, keeping the test parent active.
                val result = async {
                    runCatching { confirmation.viewModel.delete() }.exceptionOrNull()
                }
                runCurrent()
                val next = catalog.requestDelete(catalog.state.value.sessions.last())!!
                hostGate.complete(Unit)
                opening.await()
                assertIs<IllegalStateException>(result.await())
                assertSame(next, catalog.deleteTarget.value)
                assertTrue(backend.deleted.isEmpty())
            } finally {
                catalog.close()
            }
        }
    }

    test("caller cancellation restores the snapshot and does not cancel the catalog owner") {
        runTest {
            val backend = InteractionBackend()
            val catalog = DefaultSessionCatalogViewModel(this, backend)
            try {
                catalog.refresh()
                val previous = catalog.state.value
                backend.loadGate = CompletableDeferred()
                val read = async { catalog.refresh() }
                runCurrent()
                assertIs<SessionCatalogState.Loading>(catalog.state.value)
                read.cancelAndJoin()
                assertSame(previous, catalog.state.value)
                backend.loadGate = null
                catalog.refresh()
                assertIs<SessionCatalogState.Loaded>(catalog.state.value)
            } finally {
                catalog.close()
            }
        }
    }

    test("cancelled open never dismisses and close cancels work and closes the Delete child") {
        runTest {
            val backend = InteractionBackend()
            val host = InteractionHost().apply { openGate = CompletableDeferred() }
            val catalog = DefaultSessionCatalogViewModel(this, backend, host)
            catalog.refresh()
            val row = catalog.state.value.sessions.first()
            val handle = catalog.requestDelete(row)!!
            val opening = async { catalog.requestOpen(row) }
            runCurrent()
            catalog.close()
            opening.join()
            assertTrue(opening.isCancelled)
            assertEquals(listOf("open:7"), host.calls)
            assertNull(catalog.deleteTarget.value)
            assertFalse(handle.viewModel.isActive)
            catalog.dismissDelete(handle)
            catalog.close()
            assertFailsWith<CancellationException> { catalog.requestOpen(row) }
            assertFailsWith<CancellationException> { catalog.requestDelete(row) }
            assertFailsWith<CancellationException> { catalog.dismiss() }
            assertEquals(listOf("load:false"), backend.calls)
        }
    }

    test("fork reloads current filter without navigation and ordering remains backend supplied") {
        runTest {
            val backend = InteractionBackend()
            val host = InteractionHost()
            val catalog = DefaultSessionCatalogViewModel(this, backend, host)
            try {
                catalog.refresh()
                assertEquals(listOf(7, 2), catalog.state.value.sessions.map { it.sessionIndex })
                catalog.setShowArchived(true)
                catalog.setShowArchived(true)
                assertEquals(42, catalog.fork(7))
                assertEquals(emptyList(), host.calls)
                assertEquals(listOf("load:false", "load:true", "fork:7", "load:true"), backend.calls)
                catalog.dismiss()
                assertEquals(listOf("catalog"), host.dismissed)
            } finally {
                catalog.close()
            }
        }
    }
}

private class InteractionHost : SessionCatalogInteractions {
    val calls = mutableListOf<String>()
    val dismissed = mutableListOf<String>()
    var currentPopup: String? = "catalog"
    var openGate: CompletableDeferred<Unit>? = null
    var openFailure: Exception? = null
    override suspend fun openSession(sessionIndex: Int) {
        calls += "open:$sessionIndex"
        openFailure?.let { throw it }
        openGate?.await()
    }
    override fun dismissPopup() {
        calls += "dismiss:catalog"
        if (currentPopup == "catalog") {
            dismissed += "catalog"
            currentPopup = null
        }
    }
}

private class InteractionBackend : SessionCatalogDependencies {
    val calls = mutableListOf<String>()
    val deleted = mutableListOf<Int>()
    var entries = listOf(SessionCatalogEntry(7, "first"), SessionCatalogEntry(2, "second"))
    var deleteResult = true
    var loadFailure: Exception? = null
    var loadGate: CompletableDeferred<List<SessionCatalogEntry>>? = null
    var deleteGate: CompletableDeferred<Unit>? = null
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
        if (deleteResult) {
            deleted += sessionIndex
            entries = entries.filterNot { it.sessionIndex == sessionIndex }
        }
        return deleteResult
    }
}
