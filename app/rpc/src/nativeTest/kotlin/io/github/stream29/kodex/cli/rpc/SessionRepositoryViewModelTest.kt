package io.github.stream29.kodex.cli.rpc

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.sessioncatalog.DefaultSessionCatalogViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogState
import kotlin.test.*

val sessionRepositoryViewModelTest by testSuite {
    test("catalog snapshots archive flags dates and active state without creating tabs") {
        frontend {
            val index = services.global.createSession(home)
            val catalog = DefaultSessionCatalogViewModel(this, RpcSessionCatalogDependencies(services.global))
            try {
                catalog.refresh()
                val before = assertIs<SessionCatalogState.Loaded>(catalog.state.value).sessions.single()
                assertEquals("Session $index", before.threadName)
                assertNotNull(before.createdAt)
                assertNotNull(before.updatedAt)
                catalog.archive(index)
                assertTrue(assertIs<SessionCatalogState.Loaded>(catalog.state.value).sessions.isEmpty())
                catalog.setShowArchived(true)
                assertTrue(assertIs<SessionCatalogState.Loaded>(catalog.state.value).sessions.single().archived)
                assertFalse(before.archived)
                catalog.unarchive(index)
                assertFalse(assertIs<SessionCatalogState.Loaded>(catalog.state.value).sessions.single().archived)
                val fork = catalog.fork(index)
                assertEquals(2, assertIs<SessionCatalogState.Loaded>(catalog.state.value).sessions.size)
                assertTrue(catalog.delete(fork))
            } finally { catalog.close() }
            // Disposal never closes the backend repository.
            assertEquals(1, services.global.getSessionCatalog(true).size)
        }
    }
    test("catalog delegates delete exactly once to the application callback") {
        frontend {
            val index = services.global.createSession(home)
            var calls = 0
            val catalog = DefaultSessionCatalogViewModel(this, RpcSessionCatalogDependencies(services.global, deleteSession = {
                calls++
                services.global.deleteSession(it)
            }))
            try {
                assertTrue(catalog.delete(index))
                assertEquals(1, calls)
                assertTrue(assertIs<SessionCatalogState.Loaded>(catalog.state.value).sessions.isEmpty())
            } finally { catalog.close() }
        }
    }
}
