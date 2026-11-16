package io.github.stream29.kodex.cli.rpc

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.session.contract.PersistedSessionLifecycleState
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.rpc.models.AgentStateValue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

val fileSystemSessionViewModelOwnershipTest by testSuite {
    test("release disposes only the frontend and reopens a fresh view of the same backend") {
        frontend {
            val session = create("retained")
            val index = session.sessionIndex
            val first = requireNotNull(session.rootAgent.value)
            assertSame(session, views.open(index))
            views.release(index)
            assertEquals(PersistedSessionLifecycleState.Closed, session.lifecycle.value)
            assertNull(session.rootAgent.value)
            assertTrue(services.global.getSessionCatalog(true).single().isActive)
            val second = views.open(index)
            assertNotSame(session, second)
            assertNotSame(first, second.rootAgent.value)
            assertEquals("retained", second.settings.value.threadName)
        }
    }
    test("closing a running tab does not stop accepted backend work") {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        frontend(response = { entered.complete(Unit); finish.await(); answer() }) {
            val session = create()
            session.current().submit(listOf(ContentItem.InputText("run")))
            entered.await()
            views.release(session.sessionIndex)
            assertTrue(services.global.getSessionCatalog(true).single().running)
            finish.complete(Unit)
            val fresh = views.open(session.sessionIndex)
            requireNotNull(fresh.rootAgent.value).state.first { it == AgentStateValue.AssistantMessage }
        }
    }
    test("archive opening fork and delete retain their repository semantics over RPC") {
        frontend {
            val session = create("source")
            views.archive(session.sessionIndex)
            assertTrue(services.global.getSessionCatalog(true).single().archived)
            assertSame(session, views.open(session.sessionIndex))
            assertFalse(services.global.getSessionCatalog(true).single().archived)
            val fork = views.fork(session.sessionIndex)
            assertEquals("[fork] source", views.open(fork).settings.value.threadName)
            assertTrue(views.delete(session.sessionIndex))
            assertEquals(PersistedSessionLifecycleState.Closed, session.lifecycle.value)
            assertFalse(views.delete(session.sessionIndex))
            assertEquals(listOf(fork), services.global.getSessionCatalog(true).map { it.sessionIndex })
        }
    }
    test("settings commands observe subscriptions rather than writing a reply into the tab") {
        frontend {
            val session = create()
            session.rename("renamed")
            session.name.first { it == "renamed" }
            session.updateReasoningEffort(ReasoningEffort.High)
            session.settings.first { it.reasoning.effort == ReasoningEffort.High }
            assertEquals("renamed", session.settings.value.threadName)
            assertNotNull(session.readCreatedAt())
            assertNotNull(session.readUpdatedAt())
        }
    }
}
