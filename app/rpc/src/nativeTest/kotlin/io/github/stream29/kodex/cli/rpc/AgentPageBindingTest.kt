package io.github.stream29.kodex.cli.rpc

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.contract.getAskUsersAt
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.agent.contract.RequestUserInputState
import io.github.stream29.kodex.app.settings.contract.SessionSettingsDataState
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.rpc.models.SessionSpawnRequest
import io.github.stream29.kodex.rpc.models.SessionSpawnResult
import io.github.stream29.kodex.rpc.models.SessionSpawnSource
import io.github.stream29.kodex.rpc.models.SubagentSpawnRequest
import io.github.stream29.kodex.rpc.models.SubagentSpawnResult
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.io.files.Path
import kotlin.test.*

val agentPageBindingTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("same Session children retain isolated settings, ask IDs, draft receipts and Stop") {
        frontend(response = { awaitCancellation() }) {
            SystemCoroutineFileSystem.createDirectories(home)
            val spawned = services.global.spawnSession(SessionSpawnRequest(
                home.toString(), requestUserInputMode = RequestUserInputMode.AskUser,
            ))
            val root = assertIs<SessionSpawnResult.Created>(spawned, spawned.toString())
            val rootView = views.open(root.sessionId)
            val owner = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
            suspend fun child(task: String): Pair<Int, AgentViewModel> {
                val result = assertIs<SubagentSpawnResult.Created>(services.runtime.spawnSubagent(SubagentSpawnRequest(
                    SessionSpawnSource(root.rootAgentId, root.invocationId), task,
                )))
                val binding = RpcSessionBinding.create(root.sessionId, services, owner, result.agentId) { throw it }
                return result.agentId to createRpcAgentViewModel(binding, owner, global.models) { throw it }
            }
            val (aid, a) = child("fixture A")
            val (bid, b) = child("fixture B")
            val rootAgent = assertNotNull(rootView.rootAgent.value)
            var selected = a
            val settings = RpcSessionSettingsSource(a as RpcAgentViewModel, owner, { selected === a }, canRenameSession = false)
            try {
                a.running.first { it }
                b.running.first { it }
                a.cancel()
                a.running.first { !it }
                assertTrue(b.running.value)
                val oldSettings = assertIs<SessionSettingsDataState.Available>(settings.state.value).snapshot
                assertFalse(oldSettings.canRenameSession)
                assertFalse(settings.tryRenameSession(oldSettings.revision, "must not rename"))
                val cwdA = Path(home, "a")
                val cwdB = Path(home, "b")
                SystemCoroutineFileSystem.createDirectories(cwdA)
                SystemCoroutineFileSystem.createDirectories(cwdB)
                b.updateWorkingDirectory(cwdB)
                assertTrue(settings.tryUpdateConfiguration(
                    oldSettings.revision, oldSettings.configuration.copy(workingDirectory = cwdA, model = OpenAiModelId("child-a")),
                ))
                a.settings.first { it.cwd == cwdA }
                b.settings.first { it.cwd == cwdB }
                assertEquals(OpenAiModelId("child-a"), a.settings.value.model)
                assertNotEquals(a.settings.value.model, b.settings.value.model)
                assertEquals(home, rootAgent.settings.value.cwd)
                b.composer.update("keep B")
                rootAgent.composer.update("keep root")

                val ask = RequestUserInputArgs(listOf(RequestUserInputQuestion("q", "Fixture", "Answer?")))
                val askA = services.runtime.createAskUser(aid, ask)
                val askB = services.runtime.createAskUser(bid, ask)
                assertEquals(askA.askId, askB.askId)
                val pending = a.requestUserInput.state.filterIsInstance<RequestUserInputState.Pending>().first()
                assertTrue(a.requestUserInput.updateFreeForm(pending.askId, "q", "Only A"))
                val edited = assertIs<RequestUserInputState.Pending>(a.requestUserInput.state.value)
                a.requestUserInput.submit(edited.askId, edited.revision)
                a.latestIndex.first { index -> a.binding.storage.getAskUsersAt(index).single().answer != null }
                a.requestUserInput.state.first { it == RequestUserInputState.Idle }
                val bStorage = (b as RpcAgentViewModel).binding.storage
                assertNull(bStorage.getAskUsersAt(b.latestIndex.value).single().answer)
                a.cancel()
                a.running.first { !it }
                b.cancel()
                b.running.first { !it }
                val oldA = a.composer.update("send to A")
                assertNotEquals(a.composer.state.value.ownerId, b.composer.state.value.ownerId)
                assertNotEquals(rootAgent.composer.state.value.ownerId, a.composer.state.value.ownerId)
                val sent = async(start = CoroutineStart.UNDISPATCHED) { a.composer.submit(oldA) }
                selected = b
                sent.await()
                assertEquals("", a.composer.state.value.text)
                a.latestIndex.first { index ->
                    a.binding.storage.getLastCommitsIn(0..index, setOf(AgentCommitType.UserMessage), 1)
                        .any { (it.commit as? StableUserMessage)?.content == listOf(ContentItem.InputText("send to A")) }
                }
                assertEquals("keep B", b.composer.state.value.text)
                assertEquals("keep root", rootAgent.composer.state.value.text)
                val bNonce = services.runtime.getCacheNonce(bid)
                assertTrue(services.runtime.getFirstCommitsIn(bid, bNonce, 0, Int.MAX_VALUE, setOf(AgentCommitType.UserMessage))
                    .none { it.commit.toString().contains("send to A") })
                val snapshot = settings.state.value as? SessionSettingsDataState.Available
                if (snapshot != null) assertFalse(settings.tryUpdateConfiguration(
                    snapshot.snapshot.revision, snapshot.snapshot.configuration.copy(workingDirectory = cwdB),
                ))
                assertEquals(cwdA, a.settings.value.cwd)
                services.runtime.cancelRunningTurn(aid)
                a.running.first { !it }
                val nonce = services.runtime.getCacheNonce(aid)
                val aTail = services.runtime.getLatestIndex(aid)
                val bTail = services.runtime.getLatestIndex(bid)
                a.revertHistory(aTail - 1, nonce)
                assertTrue(services.runtime.getLatestIndex(aid) < aTail)
                assertEquals(bTail, services.runtime.getLatestIndex(bid))
                services.runtime.completeAgent(aid)
                assertFailsWith<io.github.stream29.kodex.utils.rpcexception.AgentCompleted> {
                    a.submit(listOf(ContentItem.InputText("No new work.")))
                }
            } finally {
                settings.close()
                b.close()
                a.close()
                owner.coroutineContext[Job]?.cancelAndJoin()
            }
        }
    }
}
