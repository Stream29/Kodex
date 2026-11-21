package io.github.stream29.kodex.cli.rpc

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.app.agent.contract.ComposerLifecycle
import io.github.stream29.kodex.app.application.contract.AgentPageActionTarget
import io.github.stream29.kodex.app.history.contract.HistoryScrollTarget
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemViewModel
import io.github.stream29.kodex.app.session.contract.AgentParentAnchor
import io.github.stream29.kodex.app.session.contract.AgentTabViewModel
import io.github.stream29.kodex.rpc.local.BackendConnectionLost
import io.github.stream29.kodex.rpc.models.SessionSpawnRequest
import io.github.stream29.kodex.rpc.models.SessionSpawnResult
import io.github.stream29.kodex.rpc.models.SessionSpawnSource
import io.github.stream29.kodex.rpc.models.SubagentSpawnRequest
import io.github.stream29.kodex.rpc.models.SubagentSpawnResult
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient
import kotlin.test.*

val subagentNavigationTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("an open child gains a completion return only when the parent indexes its notice") {
        frontend(response = { awaitCancellation() }) {
            SystemCoroutineFileSystem.createDirectories(home)
            val root = assertIs<SessionSpawnResult.Created>(services.global.spawnSession(SessionSpawnRequest(home.toString())))
            val view = application.openSession(root.sessionId)
            val parent = assertNotNull(view.rootAgent.value)
            val childId = spawn(root, "named child")
            enter(view, childId)
            val childTab = assertNotNull(application.navigation.value.selectedTab)
            val child = assertIs<RpcAgentViewModel>(childTab.agent.value)
            withTimeout(10_000) {
                child.running.first { it }
                val anchor = view.observeCompletionAnchor(childId)
                assertFalse(anchor.first())
                val completionVisible = async { anchor.first { it } }
                services.runtime.completeAgent(childId)
                completionVisible.await()
                val completion = parent.historyIndex.window.first { entries ->
                    entries.entries.any { row -> row.summary == "Subagent #$childId completed" }
                }.entries.single { it.summary == "Subagent #$childId completed" }
                application.returnToParent(selectedTarget(), AgentParentAnchor.Completion)
                assertSame(view, application.navigation.value.selectedTab)
                assertSame(parent, application.navigation.value.selectedTab?.agent?.value)
                assertSame(child, childTab.agent.value)
                assertTrue(services.runtime.getRunningTurn(childId))
                val effect = assertNotNull(parent.history.pendingScrollEffect.first { it != null })
                val destination = assertIs<HistoryScrollTarget.Item>(effect.target)
                assertEquals(completion.index, assertIs<MessageHistoryItemViewModel>(destination.item).index)
            }
        }
    }

    test("selects only opened Agents; returns by typed anchor without stopping work") {
        lateinit var raw: RpcClient
        frontend(response = { awaitCancellation() }, decorate = { raw = it; it }) {
            SystemCoroutineFileSystem.createDirectories(home)
            val root = assertIs<SessionSpawnResult.Created>(services.global.spawnSession(SessionSpawnRequest(home.toString())))
            assertNotEquals(root.sessionId, root.rootAgentId)
            val view = application.openSession(root.sessionId)
            val original = assertNotNull(view.rootAgent.value)
            original.composer.update("unsent at root")
            val a = spawn(root, "running child A")
            val b = spawn(root, "unopened child B")
            val window = original.historyIndex.window.first { it.entries.any { row -> row.createdChildAgentId == a } }
            val row = window.entries.single { it.createdChildAgentId == a }
            application.enterChild(selectedTarget(), row.index, window.generation, window.revision, b)
            assertSame(view, application.navigation.value.selectedTab)
            enter(view, a)
            val childTab = assertNotNull(application.navigation.value.selectedTab)
            val selected = assertIs<RpcAgentViewModel>(childTab.agent.value)
            assertEquals(a, selected.binding.agentId)
            assertEquals(listOf(root.rootAgentId, a), application.navigation.value.tabs.map { it.agentId })
            assertSame(original, view.rootAgent.value)
            selected.running.first { it }
            selected.composer.update("unsent at A")
            var activations = 0
            val replacement = RpcServices(object : RpcClient by raw {
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "keepAgentAlive") activations++
                    return raw.call(call)
                }
            })
            views.reactivate(replacement) { true }
            assertEquals(2, activations)
            assertSame(selected, childTab.agent.value)
            application.returnToParent(selectedTarget(), AgentParentAnchor.Completion)
            assertSame(view, application.navigation.value.selectedTab)
            assertNull(original.history.pendingScrollEffect.value)
            assertTrue(services.runtime.getRunningTurn(a))
            assertSame(childTab, application.openAgent(a))
            assertSame(selected, childTab.agent.value)
            assertEquals("unsent at A", selected.composer.state.value.text)
            assertEquals("unsent at root", original.composer.state.value.text)
            assertTrue(application.closeTab(view))
            assertSame(childTab, application.navigation.value.selectedTab)
            assertSame(selected, childTab.agent.value)
            activations = 0
            views.reactivate(replacement) { true }
            assertEquals(1, activations)
            services.global.archiveSession(root.sessionId)
            application.returnToParent(selectedTarget(), AgentParentAnchor.Creation)
            val reopened = assertNotNull(application.navigation.value.selectedTab)
            assertEquals(root.rootAgentId, reopened.agentId)
            assertNotSame(view, reopened)
            assertNotNull(reopened.agent.value?.history?.pendingScrollEffect?.first { it != null })
            assertTrue(services.global.getSessionCatalog(true).single { it.sessionIndex == root.sessionId }.archived)
            assertTrue(application.selectTab(childTab))
            assertEquals("unsent at A", childTab.agent.value?.composer?.state?.value?.text)
            assertTrue(application.closeTab(childTab))
            selected.composer.state.first { it.lifecycle == ComposerLifecycle.Closed }
            assertTrue(services.runtime.getRunningTurn(a))
            activations = 0
            views.reactivate(replacement) { true }
            assertEquals(1, activations)
            assertSame(reopened, application.navigation.value.selectedTab)
            enter(reopened, a)
            val reopenedChildTab = assertNotNull(application.navigation.value.selectedTab)
            val reopenedChild = assertNotNull(reopenedChildTab.agent.value)
            assertNotSame(childTab, reopenedChildTab)
            assertNotSame(selected, reopenedChild)
            assertEquals("", reopenedChild.composer.state.value.text)
        }
    }

    for (change in listOf("tab", "history", "close", "activation failure")) {
        test("does not publish a late child after $change changes") {
            val entered = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            var gatedAgentId: Int? = null
            frontend(response = { awaitCancellation() }, decorate = { raw ->
                object : RpcClient by raw {
                    override suspend fun <T> call(call: RpcCall): T {
                        if (call.callableName == "keepAgentAlive" && gatedAgentId != null &&
                            call.parameters.firstOrNull() == gatedAgentId && !entered.isCompleted
                        ) {
                            entered.complete(Unit)
                            proceed.await()
                            if (change == "activation failure") throw IllegalStateException("fixture activation failed")
                        }
                        return raw.call(call)
                    }
                }
            }) {
                SystemCoroutineFileSystem.createDirectories(home)
                val root = assertIs<SessionSpawnResult.Created>(services.global.spawnSession(SessionSpawnRequest(home.toString())))
                val view = application.openSession(root.sessionId)
                val original = assertNotNull(view.rootAgent.value)
                val child = spawn(root, "fixture child")
                gatedAgentId = child
                val captured = selectedTarget()
                val opening = async {
                    var result: Result<Unit>
                    do {
                        val index = original.historyIndex
                        val window = index.window.first { state ->
                            state.entries.any { row -> row.createdChildAgentId == child && index.contains(state.generation, row.index) }
                        }
                        val row = window.entries.single { it.createdChildAgentId == child }
                        result = runCatching {
                            application.enterChild(captured, row.index, window.generation, window.revision, child)
                        }
                        if (!entered.isCompleted && result.isSuccess) delay(10)
                    } while (!entered.isCompleted && result.isSuccess)
                    result
                }
                withTimeout(5_000) { entered.await() }
                when (change) {
                    "tab" -> {
                        application.selectNewSession()
                        application.selectTab(view) // ABA: selected handle alone is not a sufficient guard.
                    }
                    "history" -> {
                        val window = original.historyIndex.window.first { it.entries.any { row -> row.createdChildAgentId == child } }
                        val row = window.entries.single { it.createdChildAgentId == child }
                        original.revertHistory(row.index, window.generation)
                        original.historyIndex.window.first { it.generation != window.generation }
                    }
                    "close" -> {
                        application.closeTab(view)
                        assertNull(view.agent.value)
                    }
                }
                proceed.complete(Unit)
                val result = opening.await()
                if (change == "activation failure") assertEquals("fixture activation failed", result.exceptionOrNull()?.message)
                assertFalse(application.navigation.value.tabs.any { it.agentId == child })
                if (change != "close") assertSame(view, application.navigation.value.selectedTab)
            }
        }
    }

    test("same-numbered replacement is rejected before activating a displayed child") {
        var replaced = false
        var keepalives = 0
        frontend(response = { awaitCancellation() }, decorate = { client ->
            object : RpcClient by client {
                @Suppress("UNCHECKED_CAST")
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "keepAgentAlive") keepalives++
                    if (call.callableName == "getRootAgentId" && replaced) return Int.MAX_VALUE as T
                    return client.call(call)
                }
            }
        }) {
            SystemCoroutineFileSystem.createDirectories(home)
            val root = assertIs<SessionSpawnResult.Created>(services.global.spawnSession(SessionSpawnRequest(home.toString())))
            val view = application.openSession(root.sessionId)
            val original = assertNotNull(view.rootAgent.value)
            val child = spawn(root, "fixture child in old Session")
            val window = original.historyIndex.window.first { it.entries.any { row -> row.createdChildAgentId == child } }
            val index = window.entries.single { it.createdChildAgentId == child }.index
            val before = keepalives
            replaced = true
            assertFailsWith<IllegalStateException> {
                application.enterChild(selectedTarget(), index, window.generation, window.revision, child)
            }
            assertEquals(before, keepalives)
            assertSame(view, application.navigation.value.selectedTab)
        }
    }

    test("rebuilds an open child's binding after transport loss without replacing the tab or parent") {
        val available = MutableStateFlow(true)
        frontend(response = { awaitCancellation() }, connectionAvailable = { available.value }) {
            SystemCoroutineFileSystem.createDirectories(home)
            val root = assertIs<SessionSpawnResult.Created>(services.global.spawnSession(SessionSpawnRequest(home.toString())))
            val view = assertIs<RpcSessionView>(application.openSession(root.sessionId))
            val original = assertNotNull(view.rootAgent.value)
            val child = spawn(root, "recoverable child fixture")
            enter(view, child)
            val childTab = assertNotNull(application.navigation.value.selectedTab)
            val opened = assertIs<RpcAgentViewModel>(childTab.agent.value)
            opened.composer.update("uncommitted on previous binding")
            available.value = false
            opened.binding.failRecovery(BackendConnectionLost("fixture child transport lost"))
            childTab.agent.first { it == null }
            assertSame(original, view.rootAgent.value, "Parent before child recovery: ${view.status.value}")
            assertSame(childTab, application.navigation.value.selectedTab)
            available.value = true
            val recovered = assertIs<RpcAgentViewModel>(childTab.agent.first { it != null })
            assertNotSame(opened, recovered)
            assertEquals(child, recovered.binding.agentId)
            assertEquals("", recovered.composer.state.value.text)
            assertSame(original, view.rootAgent.value, "Parent after child recovery: ${view.status.value}")
            assertTrue(services.runtime.getRunningTurn(child))
            application.returnToParent(selectedTarget(), AgentParentAnchor.Creation)
            assertSame(view, application.navigation.value.selectedTab)
            assertSame(recovered, childTab.agent.value, "Child after returning to parent: ${childTab.lifecycle.value}")
        }
    }

    test("direct Agent open reuses the root without unarchiving its Session") {
        frontend {
            SystemCoroutineFileSystem.createDirectories(home)
            val session = services.global.createSession(home)
            val root = services.global.getRootAgentId(session)
            services.global.archiveSession(session)
            val tab = application.openAgent(root)
            assertSame(tab, application.openAgent(root))
            assertEquals(listOf(tab), application.navigation.value.tabs)
            assertTrue(services.global.getSessionCatalog(true).single { it.sessionIndex == session }.archived)
            assertSame(tab, application.openSession(session))
            assertFalse(services.global.getSessionCatalog(true).single { it.sessionIndex == session }.archived)
        }
    }

    test("a late return-anchor read cannot scroll after switching away and back") {
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        var gate = false
        frontend(response = { awaitCancellation() }, decorate = { client ->
            object : RpcClient by client {
                override suspend fun <T> call(call: RpcCall): T {
                    if (gate && call.callableName == "getLastCommitsIn" &&
                        call.parameters.any { it == setOf(AgentCommitType.EventSubagentCreated) }
                    ) {
                        entered.complete(Unit)
                        proceed.await()
                    }
                    return client.call(call)
                }
            }
        }) {
            SystemCoroutineFileSystem.createDirectories(home)
            val root = assertIs<SessionSpawnResult.Created>(services.global.spawnSession(SessionSpawnRequest(home.toString())))
            val parent = application.openSession(root.sessionId)
            enter(parent, spawn(root, "child with a late return anchor"))
            val child = assertNotNull(application.navigation.value.selectedTab)
            val source = selectedTarget()
            gate = true
            val returning = async { application.returnToParent(source, AgentParentAnchor.Creation) }
            entered.await()
            assertSame(parent, application.navigation.value.selectedTab)
            application.selectTab(child)
            application.selectTab(parent)
            proceed.complete(Unit)
            returning.await()
            assertSame(parent, application.navigation.value.selectedTab)
            assertNull(parent.agent.value?.history?.pendingScrollEffect?.value)
        }
    }
}

private fun FrontendFixture.selectedTarget(): AgentPageActionTarget {
    val selected = assertNotNull(application.navigation.value.selectedTab)
    return AgentPageActionTarget(selected, assertNotNull(selected.agent.value)) { true }
}

private suspend fun FrontendFixture.enter(parent: AgentTabViewModel, childId: Int) {
    assertSame(parent, application.navigation.value.selectedTab)
    withTimeout(5_000) {
        while (application.navigation.value.selectedTab === parent) {
            val source = selectedTarget()
            val index = source.agent.historyIndex
            val window = index.window.first { state ->
                state.entries.any { row -> row.createdChildAgentId == childId && index.contains(state.generation, row.index) }
            }
            val row = window.entries.single { it.createdChildAgentId == childId }
            application.enterChild(source, row.index, window.generation, window.revision, childId)
            // The spawn's in-flight writes can withdraw the first projected row.
            // A fresh invocation models clicking the replacement row after it renders.
            if (application.navigation.value.selectedTab === parent) delay(10)
        }
    }
    assertEquals(childId, application.navigation.value.selectedTab?.agentId)
}

private suspend fun FrontendFixture.spawn(root: SessionSpawnResult.Created, message: String): Int =
    assertIs<SubagentSpawnResult.Created>(services.runtime.spawnSubagent(SubagentSpawnRequest(
        SessionSpawnSource(root.rootAgentId, root.invocationId), message, message,
    ))).agentId
