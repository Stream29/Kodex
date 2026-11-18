package io.github.stream29.kodex.cli.rpc

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.agent.contract.ComposerLifecycle
import io.github.stream29.kodex.app.session.contract.AgentParentAnchor
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient
import kotlin.test.*

val subagentNavigationTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("selects only opened Agents; returns by typed anchor without stopping work") {
        lateinit var raw: RpcClient
        frontend(response = { awaitCancellation() }, decorate = { raw = it; it }) {
            SystemCoroutineFileSystem.createDirectories(home)
            val root = assertIs<SessionSpawnResult.Created>(services.global.spawnSession(SessionSpawnRequest(home.toString())))
            assertNotEquals(root.sessionId, root.rootAgentId)
            val view = views.open(root.sessionId)
            val original = assertNotNull(view.rootAgent.value)
            original.composer.update("unsent at root")
            val a = spawn(root, "running child A")
            val b = spawn(root, "unopened child B")
            val window = original.historyIndex.window.first { it.entries.any { row -> row.createdChildAgentId == a } }
            val row = window.entries.single { it.createdChildAgentId == a }
            view.enterChild(original, row.index, window.generation, window.revision, b) { true }
            assertSame(original, view.selectedAgent.value)
            view.enterChild(original, row.index, window.generation, window.revision, a) { true }
            val selected = assertIs<RpcAgentViewModel>(view.selectedAgent.value)
            assertEquals(a, selected.binding.agentId)
            assertEquals(listOf(root.rootAgentId, a), view.recoveryBindings().map { it.agentId })
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
            assertSame(selected, view.selectedAgent.value)
            view.returnToParent(selected, AgentParentAnchor.Completion) { true }
            assertSame(original, view.selectedAgent.value)
            assertNull(original.history.pendingScrollEffect.value)
            assertTrue(services.runtime.getRunningTurn(a))
            selected.composer.state.first { it.lifecycle == ComposerLifecycle.Closed }
            assertEquals("unsent at root", original.composer.state.value.text)
            activations = 0
            views.reactivate(replacement) { true }
            assertEquals(1, activations)
            view.enterChild(original, row.index, window.generation, window.revision, a) { true }
            val reopened = assertNotNull(view.selectedAgent.value)
            assertNotSame(selected, reopened)
            assertEquals("", reopened.composer.state.value.text)
            view.returnToParent(reopened, AgentParentAnchor.Creation) { true }
            assertSame(original, view.selectedAgent.value)
            assertNotNull(original.history.pendingScrollEffect.first { it != null })
        }
    }

    for (change in listOf("tab", "history", "close", "activation failure")) {
        test("does not publish a late child after $change changes") {
            val entered = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            var gate = false
            frontend(response = { awaitCancellation() }, decorate = { raw ->
                object : RpcClient by raw {
                    override suspend fun <T> call(call: RpcCall): T {
                        if (call.callableName == "keepAgentAlive" && gate && !entered.isCompleted) {
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
                val view = views.open(root.sessionId)
                val original = assertNotNull(view.rootAgent.value)
                val child = spawn(root, "fixture child")
                val window = original.historyIndex.window.first { it.entries.any { row -> row.createdChildAgentId == child } }
                val index = window.entries.single { it.createdChildAgentId == child }.index
                var selected = true
                gate = true
                val opening = async {
                    runCatching { view.enterChild(original, index, window.generation, window.revision, child) { selected } }
                }
                entered.await()
                when (change) {
                    "tab" -> selected = false
                    "history" -> {
                        original.revertHistory(index, window.generation)
                        original.historyIndex.window.first { it.generation != window.generation }
                    }
                    "close" -> {
                        views.release(view)
                        assertNull(view.selectedAgent.value)
                    }
                }
                proceed.complete(Unit)
                val result = opening.await()
                if (change == "activation failure") assertEquals("fixture activation failed", result.exceptionOrNull()?.message)
                assertFalse(view.recoveryBindings().any { it.agentId == child })
                if (change != "close") assertSame(original, view.selectedAgent.value)
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
            val view = views.open(root.sessionId)
            val original = assertNotNull(view.rootAgent.value)
            val child = spawn(root, "fixture child in old Session")
            val window = original.historyIndex.window.first { it.entries.any { row -> row.createdChildAgentId == child } }
            val index = window.entries.single { it.createdChildAgentId == child }.index
            val before = keepalives
            replaced = true
            assertFailsWith<IllegalStateException> {
                view.enterChild(original, index, window.generation, window.revision, child) { true }
            }
            assertEquals(before, keepalives)
            assertSame(original, view.selectedAgent.value)
        }
    }

    test("rebuilds an open child's binding after transport loss without replacing the tab or parent") {
        val available = MutableStateFlow(true)
        frontend(response = { awaitCancellation() }, connectionAvailable = { available.value }) {
            SystemCoroutineFileSystem.createDirectories(home)
            val root = assertIs<SessionSpawnResult.Created>(services.global.spawnSession(SessionSpawnRequest(home.toString())))
            val view = views.open(root.sessionId)
            val original = assertNotNull(view.rootAgent.value)
            val child = spawn(root, "recoverable child fixture")
            val window = original.historyIndex.window.first { it.entries.any { row -> row.createdChildAgentId == child } }
            val index = window.entries.single { it.createdChildAgentId == child }.index
            view.enterChild(original, index, window.generation, window.revision, child) { true }
            val opened = assertIs<RpcAgentViewModel>(view.selectedAgent.value)
            opened.composer.update("uncommitted on previous binding")
            available.value = false
            opened.binding.failRecovery(BackendConnectionLost("fixture child transport lost"))
            view.selectedAgent.first { it == null }
            assertSame(original, view.rootAgent.value)
            assertEquals(listOf(root.rootAgentId), view.recoveryBindings().map { it.agentId })
            available.value = true
            val recovered = assertIs<RpcAgentViewModel>(view.selectedAgent.first { it != null })
            assertNotSame(opened, recovered)
            assertEquals(child, recovered.binding.agentId)
            assertEquals("", recovered.composer.state.value.text)
            assertSame(original, view.rootAgent.value)
            assertTrue(services.runtime.getRunningTurn(child))
            view.returnToParent(recovered, AgentParentAnchor.Creation) { true }
            assertSame(original, view.selectedAgent.value)
            assertEquals(listOf(root.rootAgentId), view.recoveryBindings().map { it.agentId })
        }
    }
}

private suspend fun FrontendFixture.spawn(root: SessionSpawnResult.Created, message: String): Int =
    assertIs<SubagentSpawnResult.Created>(services.runtime.spawnSubagent(SubagentSpawnRequest(
        SessionSpawnSource(root.rootAgentId, root.invocationId), message,
    ))).agentId
