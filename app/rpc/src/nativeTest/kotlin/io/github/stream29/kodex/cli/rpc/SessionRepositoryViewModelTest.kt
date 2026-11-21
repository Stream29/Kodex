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
                assertEquals("Session $index", before.name)
                assertNotNull(before.createdAt)
                assertNotNull(before.updatedAt)
                catalog.archive(index)
                assertTrue(assertIs<SessionCatalogState.Loaded>(catalog.state.value).sessions.isEmpty())
                catalog.setShowArchived(true)
                assertTrue(assertIs<SessionCatalogState.Loaded>(catalog.state.value).sessions.single().archived)
                assertFalse(before.archived)
                catalog.unarchive(index)
                assertFalse(assertIs<SessionCatalogState.Loaded>(catalog.state.value).sessions.single().archived)
                val otherIndex = services.global.createSession(home)
                catalog.refresh()
                assertEquals(2, assertIs<SessionCatalogState.Loaded>(catalog.state.value).sessions.size)
                val otherEntry = catalog.state.value.sessions.single { it.sessionIndex == otherIndex }
                assertTrue(requireNotNull(catalog.requestDelete(otherEntry)).viewModel.delete())
            } finally { catalog.close() }
            // Disposal never closes the backend repository.
            assertEquals(listOf(index), services.global.getSessionCatalog(true).map { it.sessionIndex })
        }
    }
    test("catalog delegates delete confirmation exactly once to the application callback") {
        frontend {
            val index = services.global.createSession(home)
            var calls = 0
            val catalog = DefaultSessionCatalogViewModel(this, RpcSessionCatalogDependencies(services.global, deleteSession = {
                calls++
                services.global.deleteSession(it)
            }))
            try {
                catalog.refresh()
                val entry = catalog.state.value.sessions.single { it.sessionIndex == index }
                assertTrue(requireNotNull(catalog.requestDelete(entry)).viewModel.delete())
                assertEquals(1, calls)
                assertTrue(assertIs<SessionCatalogState.Loaded>(catalog.state.value).sessions.isEmpty())
            } finally { catalog.close() }
        }
    }
}
