package io.github.stream29.kodex.cli.session

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.test.*
import io.github.stream29.kodex.cli.rpc.RpcSessionCatalog
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogState
import kotlin.test.*

val sessionRepositoryViewModelTest by testSuite {
    test("catalog snapshots archive flags dates and active state without creating tabs") {
        withRpcFrontend {
            val index = services.global.createSession(testSettings("catalog", root))
            val catalog = RpcSessionCatalog(this, services.global)
            try {
                catalog.refresh()
                val before = assertIs<SessionCatalogState.Loaded>(catalog.state.value).sessions.single()
                assertEquals("Session $index", before.threadName)
                assertNotNull(catalog.readCreatedAt(index))
                assertNotNull(catalog.readUpdatedAt(index))
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
        withRpcFrontend {
            val index = services.global.createSession(testSettings("delete", root))
            var calls = 0
            val catalog = RpcSessionCatalog(this, services.global, deleteSession = {
                calls++
                services.global.deleteSession(it)
            })
            try {
                assertTrue(catalog.delete(index))
                assertEquals(1, calls)
                assertTrue(assertIs<SessionCatalogState.Loaded>(catalog.state.value).sessions.isEmpty())
            } finally { catalog.close() }
        }
    }
}
