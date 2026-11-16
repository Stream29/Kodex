@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentschedule.contract.ScheduleRecord
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserEntry
import io.github.stream29.kodex.cli.rpc.RpcServices
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.ModelsResponse
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiResult
import io.github.stream29.kodex.openai.SearchResponse
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.inmemory.withInMemoryRpc
import io.github.stream29.kodex.rpc.server.defaultBackendSettings
import io.github.stream29.kodex.rpc.server.withBackendServices
import io.github.stream29.kodex.tool.unifiedexec.KodexAgentIdEnvironmentVariable
import io.github.stream29.kodex.tool.unifiedexec.KodexHomeEnvironmentVariable
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlin.random.Random
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.builtins.ListSerializer

val cliToolExecutionTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("resolveToolBackend fails fast without auto-starting a backend when none is running") {
        val home = Path(SystemTemporaryDirectory, "kodex-cli-no-backend-${Random.nextLong()}")
        val error = assertFailsWith<IllegalStateException> {
            resolveToolBackend(
                target = null,
                defaultHome = Path("/unused"),
                env = { if (it == KodexHomeEnvironmentVariable) home.toString() else null },
                discover = { null },
            )
        }
        assertContains(error.message.orEmpty(), "No running Kodex backend found")
    }

    test("flat CLI tool commands execute end-to-end against RPC services using KODEX_AGENT_ID and --agent") {
        val root = Path(SystemTemporaryDirectory, "kodex-cli-tool-e2e-${Random.nextLong()}")
        val client = mockOpenAiClient {
            listModels {
                OpenAiResult.Success(
                    ModelsResponse(
                        listOf(
                            ModelInfo(
                                slug = OpenAiModelId("test-model"),
                                displayName = "Test Model",
                                contextWindow = 100_000,
                                maxContextWindow = 100_000,
                            ),
                        ),
                    ),
                )
            }
            search { request ->
                OpenAiResult.Success(
                    SearchResponse(
                        encryptedOutput = "opaque-side-channel-ciphertext",
                        output = "Result for ${request.commands?.searchQuery?.single()?.q}",
                    ),
                )
            }
        }
        try {
            withBackendServices(
                home = root,
                codexHome = Path(root, "codex"),
                agentsHome = Path(root, "agents"),
                defaults = defaultBackendSettings().let {
                    it.copy(
                        sessionTitle = SessionTitleSettings(false),
                        newSession = it.newSession.copy(model = OpenAiModelId("gpt-6-astra")),
                    )
                },
                createClient = { client },
            ) { backend ->
                withInMemoryRpc(backend::register) { raw ->
                    val services = RpcServices(RestoringRpcClient(raw))
                    services.global.getModelsFlow().first { it.isNotEmpty() }
                    val sessionId = services.global.createSession(root)
                    val envMap = mapOf(
                        KodexAgentIdEnvironmentVariable to sessionId.toString(),
                        KodexHomeEnvironmentVariable to root.toString(),
                    )
                    val fixedNow = Instant.parse("2099-01-01T00:00:00Z")

                    fun runCli(args: List<String>, stdinText: String = ""): List<String> {
                        val stdout = mutableListOf<String>()
                        val exitCode = dispatchCli(
                            arguments = args,
                            output = { msg, _ -> error("Unexpected parser output: $msg") },
                        ) { leaf ->
                            runBlocking {
                                executeCliToolCommand(
                                    command = leaf,
                                    services = services,
                                    env = envMap::get,
                                    readStdin = { stdinText },
                                    writeStdout = stdout::add,
                                    now = { fixedNow },
                                )
                            }
                        }
                        assertEquals(0, exitCode)
                        return stdout
                    }

                    // 1. `kodex session status` via KODEX_AGENT_ID
                    val statusJson = runCli(listOf("session", "status")).single()
                    val status = CliToolJson.decodeFromString(CliSessionStatusOutput.serializer(), statusJson)
                    assertEquals(sessionId, status.agentId)
                    assertEquals(OpenAiModelId("gpt-6-astra"), status.model)
                    assertEquals("2099-01-01T00:00:00Z", status.now)
                    assertEquals(258_400L, status.contextWindow?.effectiveWindowTokens)
                    assertEquals(100, status.contextWindow?.remainingPercent)

                    // 2. `kodex ask create --stdin`, `kodex ask list`, `kodex ask get 1`
                    val createdAskJson = runCli(
                        listOf("ask", "create", "--stdin"),
                        stdinText = """{"questions":[{"id":"q1","header":"Env","question":"Pick target?"}]}""",
                    ).single()
                    val createdAsk = CliToolJson.decodeFromString(AskUserEntry.serializer(), createdAskJson)
                    assertEquals(1, createdAsk.askId)
                    val askList = CliToolJson.decodeFromString(
                        ListSerializer(AskUserEntry.serializer()),
                        runCli(listOf("ask", "list")).single(),
                    )
                    assertEquals(listOf(createdAsk), askList)
                    val askSingle = CliToolJson.decodeFromString(
                        AskUserEntry.serializer(),
                        runCli(listOf("ask", "get", "1", "--agent", sessionId.toString())).single(),
                    )
                    assertEquals(createdAsk, askSingle)

                    // 3. `kodex schedule create`, `kodex schedule list`, `kodex schedule delete`
                    val createdSchedule = CliToolJson.decodeFromString(
                        ScheduleRecord.serializer(),
                        runCli(listOf("schedule", "create", "-d", "Check build", "--delay-seconds", "60")).single(),
                    )
                    assertEquals(sessionId, createdSchedule.agentId)
                    assertEquals(Instant.parse("2099-01-01T00:01:00Z"), createdSchedule.triggerAt)
                    val schedules = CliToolJson.decodeFromString(
                        ListSerializer(ScheduleRecord.serializer()),
                        runCli(listOf("schedule", "list")).single(),
                    )
                    assertEquals(listOf(createdSchedule), schedules)
                    runCli(listOf("schedule", "delete", createdSchedule.scheduleId.toString()))
                    assertTrue(
                        CliToolJson.decodeFromString(
                            ListSerializer(ScheduleRecord.serializer()),
                            runCli(listOf("schedule", "list")).single(),
                        ).isEmpty(),
                    )

                    // 4. `kodex search -q kotlin`
                    assertEquals(
                        listOf("Result for kotlin"),
                        runCli(listOf("search", "-q", "kotlin")),
                    )

                    // 5. `kodex tool list`
                    val toolLines = runCli(listOf("tool", "list"))
                    assertTrue(toolLines.any { it.startsWith("kodex terminal list\t") })
                    assertTrue(toolLines.any { it.startsWith("kodex ask create\t") })
                    assertTrue(toolLines.any { it.startsWith("kodex schedule create\t") })
                    assertTrue(toolLines.any { it.startsWith("kodex session status\t") })
                    assertTrue(toolLines.any { it.startsWith("kodex search\t") })
                    assertTrue(toolLines.any { it.startsWith("kodex image\t") })
                    assertTrue(toolLines.any { it.startsWith("kodex mcp list\t") })
                }
            }
        } finally {
            suspend fun removeTree(path: Path) {
                val meta = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
                if (meta.isDirectory) SystemCoroutineFileSystem.list(path).forEach { removeTree(it) }
                SystemCoroutineFileSystem.delete(path, mustExist = false)
            }
            removeTree(root)
        }
    }
}
