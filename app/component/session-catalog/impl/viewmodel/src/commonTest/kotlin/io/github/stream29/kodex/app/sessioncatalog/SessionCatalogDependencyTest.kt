@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.app.sessioncatalog

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.sessioncatalog.contract.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

val sessionCatalogDependencyTest by testSuite {
    test("construction is lazy and every mutation reloads through the declared port") {
        runTest {
            val backend = CatalogBackend()
            val catalog = DefaultSessionCatalogViewModel(this, backend)
            try {
                assertEquals(SessionCatalogState.Unloaded, catalog.state.value)
                assertEquals(emptyList(), backend.calls)
                catalog.refresh()
                assertEquals(backend.entry.createdAt, catalog.readCreatedAt(1))
                assertEquals(backend.entry.updatedAt, catalog.readUpdatedAt(1))
                assertEquals(listOf("load:false"), backend.calls)
                catalog.setShowArchived(false)
                assertEquals(1, backend.calls.size)
                catalog.archive(1)
                assertTrue(catalog.state.value.sessions.isEmpty())
                catalog.setShowArchived(true)
                assertTrue(catalog.state.value.sessions.single().archived)
                catalog.unarchive(1)
                assertEquals(2, catalog.fork(1))
                assertTrue(catalog.delete(1))
                assertEquals(
                    listOf("load:false", "archive:1", "load:false", "load:true",
                        "unarchive:1", "load:true", "fork:1", "load:true", "delete:1", "load:true"),
                    backend.calls,
                )
            } finally {
                catalog.close()
            }
            assertFailsWith<CancellationException> { catalog.refresh() }
        }
    }

    test("loading retains prior snapshot and a concurrent mutation waits for its completion") {
        runTest {
            val backend = CatalogBackend()
            val catalog = DefaultSessionCatalogViewModel(this, backend)
            try {
                catalog.refresh()
                val previous = catalog.state.value.sessions
                val gate = CompletableDeferred<List<SessionCatalogEntry>>()
                backend.pending = gate
                val refresh = async { catalog.refresh() }
                runCurrent()
                assertEquals(previous, assertIs<SessionCatalogState.Loading>(catalog.state.value).sessions)
                val archive = async { catalog.archive(1) }
                runCurrent()
                assertEquals(listOf("load:false", "load:false"), backend.calls)
                backend.pending = null
                gate.complete(previous)
                refresh.await()
                archive.await()
                assertEquals(listOf("load:false", "load:false", "archive:1", "load:false"), backend.calls)
                assertTrue(catalog.state.value.sessions.isEmpty())
            } finally {
                catalog.close()
            }
        }
    }

    test("failed reload restores the snapshot and can be retried without cancelling the owner") {
        runTest {
            val backend = CatalogBackend()
            val catalog = DefaultSessionCatalogViewModel(this, backend)
            try {
                catalog.refresh()
                val previous = catalog.state.value
                backend.failLoad = true
                assertFailsWith<IllegalStateException> { catalog.refresh() }
                assertEquals(previous, catalog.state.value)
                backend.failLoad = false
                catalog.refresh()
                assertIs<SessionCatalogState.Loaded>(catalog.state.value)
            } finally {
                catalog.close()
            }
        }
    }
}

private class CatalogBackend : SessionCatalogDependencies {
    val calls = mutableListOf<String>()
    var entry = SessionCatalogEntry(1, createdAt = Instant.parse("2026-01-01T00:00:00Z"))
    var pending: CompletableDeferred<List<SessionCatalogEntry>>? = null
    var failLoad = false

    override suspend fun load(showArchived: Boolean): List<SessionCatalogEntry> {
        calls += "load:$showArchived"
        check(!failLoad) { "Injected catalog read failure" }
        return pending?.await() ?: listOf(entry).filter { showArchived || !it.archived }
    }

    override suspend fun archive(sessionIndex: Int) {
        calls += "archive:$sessionIndex"
        entry = entry.copy(archived = true)
    }

    override suspend fun unarchive(sessionIndex: Int) {
        calls += "unarchive:$sessionIndex"
        entry = entry.copy(archived = false)
    }

    override suspend fun fork(sessionIndex: Int): Int {
        calls += "fork:$sessionIndex"
        return 2
    }

    override suspend fun delete(sessionIndex: Int): Boolean {
        calls += "delete:$sessionIndex"
        return true
    }
}
