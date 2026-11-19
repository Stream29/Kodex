package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.cli.rpc.RpcServices
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.inmemory.withInMemoryRpc
import io.github.stream29.kodex.rpc.models.SessionSpawnRequest
import io.github.stream29.kodex.rpc.models.SessionSpawnResult
import io.github.stream29.kodex.rpc.models.SessionSpawnSource
import io.github.stream29.kodex.rpc.models.SubagentSpawnRequest
import io.github.stream29.kodex.rpc.models.SubagentSpawnResult
import io.github.stream29.kodex.rpc.server.defaultBackendSettings
import io.github.stream29.kodex.rpc.server.withBackendServices
import io.github.stream29.kodex.tool.unifiedexec.KodexAgentIdEnvironmentVariable
import io.github.stream29.kodex.tool.unifiedexec.KodexInvocationIdEnvironmentVariable
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.*
import kotlin.random.Random
import kotlin.test.*

val cliAgentTargetingTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("existing Agent commands act on a child and never use a same-numbered Session or ambient owner") {
        val root = Path(SystemTemporaryDirectory, "kodex-cli-targeting-${Random.nextLong().toULong()}")
        val fs = SystemCoroutineFileSystem
        try {
            fs.createDirectories(root)
            withBackendServices(
                root, Path(root, "codex"), Path(root, "agents"),
                defaults = defaultBackendSettings().copy(sessionTitle = SessionTitleSettings(false)),
                createClient = { mockOpenAiClient { createResponse { flow { awaitCancellation() } } } },
            ) { backend ->
                suspend fun newSession() = assertIs<SessionSpawnResult.Created>(
                    backend.global.spawnSession(SessionSpawnRequest(root.toString())),
                )
                val parent = newSession()
                val child = assertIs<SubagentSpawnResult.Created>(backend.runtime.spawnSubagent(SubagentSpawnRequest(
                    SessionSpawnSource(parent.rootAgentId, parent.invocationId), "initial child task", "Target fixture",
                )))
                val unrelated = List(3) { newSession() }.single { it.sessionId == child.agentId }
                assertNotEquals(unrelated.rootAgentId, child.agentId)
                val parentTail = backend.runtime.getLatestIndex(parent.rootAgentId)

                withInMemoryRpc(backend::register) { raw ->
                    val services = RpcServices(RestoringRpcClient(raw))
                    suspend fun issue(operation: String, id: Int, vararg arguments: String, expectedExit: Int = 0): CliOperationEnvelope {
                        val lines = mutableListOf<String>()
                        val exit = dispatchCli(listOf("agent", operation, "--agent", id.toString()) + arguments,
                            { diagnostic, _ -> error(diagnostic) },
                        ) {
                            executeCliToolCommand(assertIs<KodexToolLeafCommand>(it), services, env = { name ->
                                when (name) {
                                    KodexAgentIdEnvironmentVariable -> parent.rootAgentId.toString()
                                    KodexInvocationIdEnvironmentVariable -> parent.invocationId
                                    else -> null
                                }
                            }, writeStdout = lines::add)
                        }
                        assertEquals(expectedExit, exit, lines.toString())
                        return CliToolJson.decodeFromString(CliOperationEnvelope.serializer(), lines.single())
                    }
                    suspend fun running(expected: Boolean) = withTimeout(10_000) {
                        backend.runtime.getRunningTurnFlow(child.agentId).first { it == expected }
                    }
                    suspend fun childMessages(): List<String> {
                        val id = child.agentId
                        val latest = backend.runtime.getLatestIndex(id)
                        return backend.runtime.getFirstCommitsIn(
                            id, backend.runtime.getCacheNonce(id), 0, latest, setOf(AgentCommitType.UserMessage), 5,
                        ).map { (it.commit as StableUserMessage).content.filterIsInstance<ContentItem.InputText>().single().text }
                    }
                    running(true)
                    withTimeout(10_000) {
                        backend.runtime.getLatestIndexFlow(child.agentId).first { childMessages() == listOf("initial child task") }
                    }
                    val status = checkNotNull(issue("status", child.agentId).result).jsonObject
                    assertEquals(child.agentId, status.getValue("agent_id").jsonPrimitive.int)
                    assertTrue(status.getValue("running").jsonPrimitive.boolean)
                    assertTrue(issue("stop", child.agentId).ok)
                    running(false)
                    val beforeResume = childMessages()
                    assertEquals(listOf("initial child task"), beforeResume)
                    assertTrue(issue("resume", child.agentId).ok)
                    running(true)
                    assertEquals(beforeResume, childMessages())
                    issue("stop", child.agentId)
                    running(false)
                    assertTrue(issue("submit", child.agentId, "--message", "another child task").ok)
                    withTimeout(10_000) {
                        backend.runtime.getLatestIndexFlow(child.agentId).first { "another child task" in childMessages() }
                    }
                    assertEquals(parentTail, backend.runtime.getLatestIndex(parent.rootAgentId))
                    assertEquals(0, backend.runtime.getLatestIndex(unrelated.rootAgentId))
                    assertEquals("target_not_found", issue("status", Int.MAX_VALUE, expectedExit = 1).error?.code)
                    assertTrue(issue("complete", child.agentId).ok)
                    assertEquals("agent_completed", issue("submit", child.agentId, "--message", "reject", expectedExit = 1).error?.code)
                    assertEquals("agent_completed", issue("resume", child.agentId, expectedExit = 1).error?.code)
                    assertTrue(issue("stop", child.agentId).ok)
                    assertTrue(issue("status", child.agentId).ok)
                }
            }
        } finally {
            suspend fun delete(path: Path) {
                val metadata = fs.metadataOrNull(path) ?: return
                if (metadata.isDirectory) fs.list(path).forEach { delete(it) }
                fs.delete(path, mustExist = false)
            }
            delete(root)
        }
    }
}
