@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentschedule.contract.ScheduleRecord
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.backend.BackendEndpoint
import io.github.stream29.kodex.cli.rpc.RpcServices
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.mcp.contract.McpAuthenticationState
import io.github.stream29.kodex.mcp.contract.McpCatalogTool
import io.github.stream29.kodex.mcp.contract.McpClientFailureReason
import io.github.stream29.kodex.mcp.contract.McpClientState
import io.github.stream29.kodex.mcp.contract.McpManagedServerState
import io.github.stream29.kodex.mcp.contract.McpTransportKind
import io.github.stream29.kodex.openai.CallToolResult
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.ModelsResponse
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiResult
import io.github.stream29.kodex.openai.SearchResponse
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.contract.AgentRuntimeRpc
import io.github.stream29.kodex.rpc.inmemory.withInMemoryRpc
import io.github.stream29.kodex.rpc.models.AgentSqlError
import io.github.stream29.kodex.rpc.models.AgentSqlQueryResult
import io.github.stream29.kodex.rpc.models.AgentSqlResultJson
import io.github.stream29.kodex.rpc.models.SessionSpawnRequest
import io.github.stream29.kodex.rpc.models.SessionSpawnResult
import io.github.stream29.kodex.rpc.models.SubagentSpawnRequest
import io.github.stream29.kodex.rpc.models.SubagentSpawnResult
import io.github.stream29.kodex.rpc.models.SubagentSpawnStage
import io.github.stream29.kodex.rpc.server.defaultBackendSettings
import io.github.stream29.kodex.rpc.server.withBackendServices
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputAnswer
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.tool.unifiedexec.KodexAgentIdEnvironmentVariable
import io.github.stream29.kodex.tool.unifiedexec.KodexHomeEnvironmentVariable
import io.github.stream29.kodex.tool.unifiedexec.KodexInvocationIdEnvironmentVariable
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlin.random.Random
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.rpc.registerService
import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.StringPropertyDefinition
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int

val cliToolExecutionTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("agent spawn transports the caller, persists a child, and distinguishes a known partial result") {
        val root = Path(SystemTemporaryDirectory, "kodex-cli-subagent-${Random.nextLong().toULong()}")
        val fs = SystemCoroutineFileSystem
        try {
            fs.createDirectories(root)
            withBackendServices(
                root, Path(root, "codex"), Path(root, "agents"),
                defaults = defaultBackendSettings().copy(sessionTitle = SessionTitleSettings(false)),
                createClient = { mockOpenAiClient { createResponse { flow { awaitCancellation() } } } },
            ) { backend ->
                val response = backend.global.spawnSession(SessionSpawnRequest(root.toString()))
                val created = assertIs<SessionSpawnResult.Created>(response, response.toString())
                val runtime = object : AgentRuntimeRpc by backend.runtime {
                    override suspend fun spawnSubagent(request: SubagentSpawnRequest): SubagentSpawnResult {
                        assertEquals("CLI child", request.name)
                        val answer = backend.runtime.spawnSubagent(request)
                        // Simulate a known partial reply at the transport boundary, on a real allocated ID.
                        if (request.message != "partial fixture" || answer !is SubagentSpawnResult.Created) return answer
                        return SubagentSpawnResult.Partial(answer.sessionId, answer.parentAgentId, answer.agentId, SubagentSpawnStage.Submit)
                    }
                }
                withInMemoryRpc(registerServices = { registerService(AgentRuntimeRpc::class) { runtime } }) { raw ->
                    val services = RpcServices(RestoringRpcClient(raw))
                    suspend fun spawn(text: String, invocation: String?, expectedExit: Int): CliOperationEnvelope {
                        val lines = mutableListOf<String>()
                        val exit = dispatchCli(listOf("agent", "spawn", "--name", "CLI child", "--stdin"), { diagnostic, _ -> error(diagnostic) }) {
                            executeCliToolCommand(assertIs<KodexToolLeafCommand>(it), services, env = { name ->
                                when (name) {
                                    KodexAgentIdEnvironmentVariable -> created.rootAgentId.toString()
                                    KodexInvocationIdEnvironmentVariable -> invocation
                                    else -> null
                                }
                            }, readStdin = { text }, writeStdout = lines::add)
                        }
                        assertEquals(expectedExit, exit, lines.toString())
                        return CliToolJson.decodeFromString(CliOperationEnvelope.serializer(), lines.single())
                    }
                    assertEquals("invalid_source", spawn("task", null, 1).error?.code)
                    assertEquals("invalid_source", spawn("task", "stale", 1).error?.code)
                    val receipt = checkNotNull(spawn("valid fixture", created.invocationId, 0).result).jsonObject
                    assertEquals(created.sessionId, receipt.getValue("session_id").jsonPrimitive.int)
                    assertEquals(created.rootAgentId, receipt.getValue("parent_agent_id").jsonPrimitive.int)
                    val id = receipt.getValue("agent_id").jsonPrimitive.int
                    assertTrue(id != created.rootAgentId)
                    withTimeout(10_000) { backend.runtime.getRunningTurnFlow(id).first { it } }
                    val partial = checkNotNull(spawn("partial fixture", created.invocationId, 1).error)
                    assertEquals("subagent_partial", partial.code)
                    assertEquals("submit", partial.phase)
                    assertEquals(created.sessionId, partial.sessionId)
                    assertEquals(created.rootAgentId, partial.parentAgentId)
                    assertTrue(checkNotNull(partial.agentId) > id)
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

    test("agent complete verifies its implicit caller and does not use caller context for an explicit target") {
        val root = Path(SystemTemporaryDirectory, "kodex-cli-complete-${Random.nextLong().toULong()}")
        val fs = SystemCoroutineFileSystem
        try {
            fs.createDirectories(root)
            withBackendServices(
                root, Path(root, "codex"), Path(root, "agents"),
                defaults = defaultBackendSettings().copy(sessionTitle = SessionTitleSettings(false)),
                createClient = { mockOpenAiClient() },
            ) { backend ->
                suspend fun newSession() = assertIs<SessionSpawnResult.Created>(
                    backend.global.spawnSession(SessionSpawnRequest(root.toString())),
                )
                val caller = newSession()
                val other = newSession()
                withInMemoryRpc(registerServices = { registerService(AgentRuntimeRpc::class) { backend.runtime } }) { raw ->
                    val services = RpcServices(RestoringRpcClient(raw))
                    suspend fun complete(invocation: String?, expectedExit: Int, explicit: Int? = null): CliOperationEnvelope {
                        val args = listOf("agent", "complete") + (explicit?.let { listOf("--agent", it.toString()) } ?: emptyList())
                        val lines = mutableListOf<String>()
                        val exit = dispatchCli(args, { diagnostic, _ -> error(diagnostic) }) {
                            executeCliToolCommand(assertIs<KodexToolLeafCommand>(it), services, env = { name ->
                                when (name) {
                                    KodexAgentIdEnvironmentVariable -> invocation?.let { caller.rootAgentId.toString() }
                                    KodexInvocationIdEnvironmentVariable -> invocation
                                    else -> null
                                }
                            }, writeStdout = lines::add)
                        }
                        assertEquals(expectedExit, exit, lines.toString())
                        return CliToolJson.decodeFromString(CliOperationEnvelope.serializer(), lines.single())
                    }
                    assertEquals("invalid_source", complete(null, 1).error?.code)
                    assertEquals("invalid_source", complete("stale", 1).error?.code)
                    assertEquals(2, backend.global.getSessionCatalog(false).size)
                    assertEquals(other.rootAgentId, checkNotNull(complete("stale", 0, other.rootAgentId).result)
                        .jsonObject.getValue("agent_id").jsonPrimitive.int)
                    assertEquals(listOf(caller.sessionId), backend.global.getSessionCatalog(false).map { it.sessionIndex })
                    assertEquals("true", checkNotNull(complete(caller.invocationId, 0).result)
                        .jsonObject.getValue("completed").jsonPrimitive.content)
                    assertTrue(backend.global.getSessionCatalog(false).isEmpty())
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

    test("spawn preserves raw text, keeps accepted turns alive, and reports a known id on lost submit reply") {
        val root = Path(SystemTemporaryDirectory, "kodex-cli-spawn-${Random.nextLong().toULong()}")
        var loseSubmitReply = false
        var submits = 0
        var concurrentTitle: String? = null
        var concurrentSettingsUpdate = false
        val fs = SystemCoroutineFileSystem
        val unrelatedDirectory = Path(root, "unrelated-directory")
        try {
            withBackendServices(root, Path(root, "codex"), Path(root, "agents"),
                defaults = defaultBackendSettings().copy(sessionTitle = SessionTitleSettings(false)),
                createClient = { mockOpenAiClient { createResponse { flow { awaitCancellation() } } } },
            ) { backend ->
                fs.createDirectories(unrelatedDirectory)
                val runtime = object : AgentRuntimeRpc by backend.runtime {
                    override suspend fun submit(sessionIndex: Int, content: List<ContentItem>, expectedInvocationId: String?) {
                        submits++
                        backend.runtime.submit(sessionIndex, content, expectedInvocationId)
                        if (loseSubmitReply) error("Fixture reply lost")
                    }
                    override suspend fun compareAndSetSettings(sessionIndex: Int, expect: KodexAgentSettings, update: KodexAgentSettings): Boolean {
                        if (concurrentSettingsUpdate) {
                            concurrentSettingsUpdate = false
                            assertTrue(backend.runtime.compareAndSetSettings(sessionIndex, expect,
                                expect.copy(cwd = unrelatedDirectory, name = concurrentTitle ?: expect.name)))
                        }
                        return backend.runtime.compareAndSetSettings(sessionIndex, expect, update)
                    }
                }
                suspend fun cli(args: List<String>, stdin: String, expectedExit: Int): CliOperationEnvelope {
                    val lines = mutableListOf<String>()
                    withInMemoryRpc(registerServices = {
                        registerService(GlobalRpc::class) { backend.global }
                        registerService(AgentRuntimeRpc::class) { runtime }
                    }) { raw ->
                        val services = RpcServices(RestoringRpcClient(raw))
                        val exit = dispatchCli(args, { message, _ -> error(message) }) { leaf ->
                            executeCliToolCommand(assertIs<KodexToolLeafCommand>(leaf), services,
                                env = { null }, readStdin = { stdin }, writeStdout = lines::add)
                        }
                        assertEquals(expectedExit, exit, "${args.take(2)}: $lines")
                    }
                    assertFalse(lines.single().contains(stdin.takeIf(String::isNotEmpty) ?: "Fixture reply lost"))
                    return CliToolJson.decodeFromString(CliOperationEnvelope.serializer(), lines.single())
                }
                val created = cli(listOf("session", "spawn", "--cwd", root.toString()), "", 0)
                val id = checkNotNull(created.result).jsonObject.getValue("root_agent_id").jsonPrimitive.int
                assertEquals("false", created.result.jsonObject.getValue("submitted").jsonPrimitive.content)
                assertEquals(0, backend.runtime.getLatestIndex(id))
                assertFalse(cli(listOf("agent", "submit", "--agent", "$id", "--stdin"), "", 1).ok)
                val text = " \n  literal α\n"
                assertTrue(cli(listOf("agent", "submit", "--agent", "$id", "--stdin"), text, 0).ok)
                withTimeout(10_000) {
                    backend.runtime.getRunningTurnFlow(id).first { it }
                    backend.runtime.getLatestIndexFlow(id).first { index ->
                        val nonce = backend.runtime.getCacheNonce(id)
                        val items = backend.runtime.getLastCommitsIn(id, nonce, 0, index, setOf(AgentCommitType.UserMessage), 1)
                        (items.singleOrNull()?.commit as? StableUserMessage)?.content == listOf(ContentItem.InputText(text))
                    }
                }
                assertTrue(cli(listOf("agent", "stop", "--agent", "$id"), "", 0).ok)
                loseSubmitReply = true
                val unknown = cli(listOf("session", "spawn", "--cwd", root.toString(), "--stdin"), "private text", 1)
                val error = checkNotNull(unknown.error)
                assertEquals(CliOperationOutcome.Unknown, error.outcome)
                assertEquals("submit", error.phase)
                assertEquals(backend.global.getRootAgentId(checkNotNull(error.sessionId)), error.agentId)
                assertTrue(backend.global.getSessionCatalog(true).any { it.sessionIndex == error.sessionId })
                assertEquals(2, submits, "Do not retry an unknown mutation.")

                val ask = backend.runtime.createAskUser(id, RequestUserInputArgs(
                    questions = listOf(RequestUserInputQuestion(id = "q", header = "Fixture", question = "Which value?")),
                ))
                val answer = CliToolJson.encodeToString(RequestUserInputResponse.serializer(),
                    RequestUserInputResponse(answers = mapOf("q" to RequestUserInputAnswer(listOf("private answer")))))
                val answerArgs = listOf("ask", "answer", "--agent", "$id", "${ask.askId}", "--stdin")
                assertTrue(cli(answerArgs, answer, 0).ok)
                assertEquals("already_answered", cli(answerArgs, answer, 1).error?.code)
                assertEquals("ask_not_found", cli(listOf("ask", "answer", "--agent", "$id", "999", "--stdin"), answer, 1).error?.code)

                val target = checkNotNull(error.agentId)
                val targetSession = checkNotNull(error.sessionId)
                concurrentSettingsUpdate = true
                val rename = cli(listOf("session", "rename", "--session", "$targetSession", "--name", " first title "), "", 0)
                assertTrue(rename.ok)
                val renameResult = checkNotNull(rename.result).jsonObject
                assertEquals("first title", renameResult.getValue("name").jsonPrimitive.content)
                assertFalse("thread_name" in renameResult)
                assertFalse("threadName" in renameResult)
                val renamed = cli(listOf("agent", "status", "--agent", "$target"), "", 0).result?.jsonObject
                assertEquals(unrelatedDirectory.toString(), renamed?.get("cwd")?.jsonPrimitive?.content)
                concurrentTitle = "frontend title"
                concurrentSettingsUpdate = true
                assertEquals("rename_conflict",
                    cli(listOf("session", "rename", "--session", "$targetSession", "--name", "CLI overwrite"), "", 1).error?.code)
                fun CliOperationEnvelope.result(key: String): String? = result?.jsonObject?.get(key)?.jsonPrimitive?.content
                assertTrue(cli(listOf("session", "archive", "--session", "$targetSession"), "", 0).ok)
                val archived = backend.global.getSessionCatalog(true).single { it.sessionIndex == targetSession }
                assertTrue(archived.archived)
                assertEquals("frontend title", archived.name)
                assertTrue(cli(listOf("session", "unarchive", "--session", "$targetSession"), "", 0).ok)
                assertFalse(backend.global.getSessionCatalog(true).single { it.sessionIndex == targetSession }.archived)
                assertTrue(cli(listOf("agent", "stop", "--agent", "$target"), "", 0).ok)
                backend.runtime.getRunningTurnFlow(target).first { !it }
                val index = backend.runtime.getLatestIndex(target)
                assertEquals(index.toString(), cli(listOf("agent", "clear-pending", "--agent", "$target"), "", 0).result("latest_index"))
                val forked = cli(listOf("agent", "fork", "--agent", "$target"), "", 0)
                val fork = forked.result("session_id")!!.toInt()
                assertEquals(backend.global.getRootAgentId(fork).toString(), forked.result("root_agent_id"))
                assertTrue(fork != targetSession && backend.global.getSessionCatalog(true).any { it.sessionIndex == fork })
                assertEquals("true", cli(listOf("session", "delete", "--session", "$fork"), "", 0).result("deleted"))
                assertEquals("false", cli(listOf("session", "delete", "--session", "$fork"), "", 0).result("deleted"))
                assertTrue(backend.global.getSessionCatalog(true).any { it.sessionIndex == targetSession })
                val sqlOutput = mutableListOf<String>()
                withInMemoryRpc(registerServices = {
                    registerService(GlobalRpc::class) {
                        object : GlobalRpc by backend.global {
                            override suspend fun queryAgentSql(sql: String): AgentSqlQueryResult = error("Fixture SQL diagnostic")
                        }
                    }
                }) { raw ->
                    val exit = dispatchCli(listOf("agent", "sql", "--stdin"), { message, _ -> error(message) }) { leaf ->
                        executeCliToolCommand(assertIs<KodexToolLeafCommand>(leaf), RpcServices(RestoringRpcClient(raw)),
                            readStdin = { "SELECT 1" }, writeStdout = sqlOutput::add)
                    }
                    assertEquals(1, exit)
                }
                val output = sqlOutput.single()
                assertEquals(AgentSqlError.UNAVAILABLE, AgentSqlResultJson.decodeFromString(AgentSqlQueryResult.serializer(), output).error?.code)
                assertFalse(output.contains("Fixture SQL diagnostic"))
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
    test("Session queries print one sanitized failure receipt even before the RPC opens") {
        val replies = mutableListOf<String>()
        val exit = dispatchCliWithToolRpc(
            arguments = listOf("agent", "status", "--agent", "7"),
            defaultHome = Path(SystemTemporaryDirectory, "unused"),
            output = { value, error -> assertFalse(error); replies += value },
            env = { null },
            ensure = { error("fixture-only upstream details") },
            openServices = { _, _, _ -> error("Must not open a failed discovery") },
        ) { leaf, invoke -> invoke(assertIs<KodexToolLeafCommand>(leaf)) }
        assertEquals(1, exit)
        val output = replies.single()
        assertFalse("fixture-only" in output)
        val envelope = CliToolJson.decodeFromString(CliOperationEnvelope.serializer(), output)
        assertFalse(envelope.ok)
        assertEquals("connect", envelope.error?.phase)
        assertEquals(7, envelope.error?.agentId)
    }
    test("resolveToolBackend ensures a backend unless --backend-url is explicit") {
        val home = Path(SystemTemporaryDirectory, "kodex-cli-ensure-backend-${Random.nextLong()}")
        val expected = BackendEndpoint("instance-ensure", 42, 18080, "a".repeat(64), "0.6.0")
        var ensuredHome: Path? = null
        val (resolvedDefaultHome, resolvedDefaultEndpoint) = resolveToolBackend(
            target = null,
            defaultHome = Path("/unused"),
            env = { if (it == KodexHomeEnvironmentVariable) home.toString() else null },
            ensure = { candidate ->
                ensuredHome = candidate
                expected
            },
            connect = { _, _ -> error("Default target must ensure instead of connectBackend") },
        )
        assertEquals(home, resolvedDefaultHome)
        assertEquals(home, ensuredHome)
        assertEquals(expected, resolvedDefaultEndpoint)

        var connectedTarget: Pair<Path, Int>? = null
        val (resolvedExplicitHome, resolvedExplicitEndpoint) = resolveToolBackend(
            target = CliBackendTarget(port = 18080, backendHome = home.toString()),
            defaultHome = Path("/unused"),
            env = { null },
            ensure = { error("Explicit --backend-url must not auto-start a backend") },
            connect = { candidate, port ->
                connectedTarget = candidate to port
                expected
            },
        )
        assertEquals(home, resolvedExplicitHome)
        assertEquals(home to 18080, connectedTarget)
        assertEquals(expected, resolvedExplicitEndpoint)
    }

    test("dynamic MCP tool invocation reuses a single RPC connection across discovery and execution") {
        val endpoint = BackendEndpoint("instance-mcp", 42, 18080, "a".repeat(64), "0.6.0")
        val root = Path(SystemTemporaryDirectory, "kodex-cli-single-conn-${Random.nextLong()}")
        val client = mockOpenAiClient {
            listModels { OpenAiResult.Success(ModelsResponse(emptyList())) }
        }
        try {
            withBackendServices(
                home = root,
                codexHome = Path(root, "codex"),
                agentsHome = Path(root, "agents"),
                defaults = defaultBackendSettings().copy(sessionTitle = SessionTitleSettings(false)),
                createClient = { client },
            ) { backend ->
                var opened = 0
                var closed = 0
                val published = McpCatalogTool(
                    serverName = "github",
                    serverInstructions = "GitHub MCP server.",
                    toolName = "echo_title",
                    description = "Echoes title.",
                    inputSchema = ObjectPropertyDefinition(
                        properties = mapOf("title" to StringPropertyDefinition(description = "Title")),
                        required = listOf("title"),
                    ),
                )
                withInMemoryRpc(backend::register) { baseRaw ->
                    val baseServices = RpcServices(RestoringRpcClient(baseRaw))
                    val customGlobal = object : GlobalRpc by baseServices.global {
                        override suspend fun listMcpTools(): List<McpCatalogTool> = listOf(published)
                        override suspend fun callMcpTool(
                            serverName: String,
                            toolName: String,
                            arguments: JsonObject,
                        ): CallToolResult {
                            assertEquals("github", serverName)
                            assertEquals("echo_title", toolName)
                            return CallToolResult(
                                content = listOf(
                                    kotlinx.serialization.json.buildJsonObject {
                                        put("type", kotlinx.serialization.json.JsonPrimitive("text"))
                                        put("text", arguments.getValue("title"))
                                    },
                                ),
                            )
                        }
                    }
                    withInMemoryRpc(registerServices = {
                        registerService<GlobalRpc> { customGlobal }
                        registerService<io.github.stream29.kodex.rpc.contract.AgentRuntimeRpc> { baseServices.runtime }
                    }) { mcpRaw ->
                        val mcpServices = RpcServices(RestoringRpcClient(mcpRaw))
                        val stdout = mutableListOf<String>()
                        val code = dispatchCliWithToolRpc(
                            arguments = listOf("github", "echo_title", "--json", """{"title":"one-connection"}"""),
                            defaultHome = root,
                            output = { msg, _ -> error("Unexpected parser output: $msg") },
                            ensure = { endpoint },
                            openServices = { _, _, use ->
                                opened++
                                try {
                                    use(mcpServices)
                                } finally {
                                    closed++
                                }
                            },
                            executeTool = { toolLeaf, services ->
                                assertEquals(1, opened)
                                assertEquals(0, closed)
                                executeCliToolCommand(toolLeaf, services, writeStdout = stdout::add)
                            },
                        ) { leaf, invokeTool ->
                            invokeTool(assertIs<KodexToolLeafCommand>(leaf))
                        }
                        assertEquals(0, code)
                        assertEquals(1, opened)
                        assertEquals(1, closed)
                        val result = CliToolJson.decodeFromString(CallToolResult.serializer(), stdout.single())
                        assertEquals("one-connection", result.content.single().jsonObject.getValue("text").jsonPrimitive.content)

                        // Help also closes its single discovery connection deterministically.
                        var helpOpened = 0
                        var helpClosed = 0
                        val helpMessages = mutableListOf<String>()
                        val helpCode = dispatchCliWithToolRpc(
                            arguments = listOf("github", "--help"),
                            defaultHome = root,
                            output = { msg, err ->
                                assertFalse(err)
                                helpMessages += msg
                            },
                            ensure = { endpoint },
                            openServices = { _, _, use ->
                                helpOpened++
                                try { use(mcpServices) } finally { helpClosed++ }
                            },
                        ) { _, _ ->
                            error("Help must not execute")
                        }
                        assertEquals(0, helpCode)
                        assertEquals(1, helpOpened)
                        assertEquals(1, helpClosed)
                        assertContains(helpMessages.single(), "echo_title")
                    }
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

    test("unknown top-level commands treat target as MCP, wait through cold Connecting, and report actionable diagnostics") {
        val endpoint = BackendEndpoint("instance-mcp-diag", 42, 18081, "b".repeat(64), "0.6.0")
        val root = Path(SystemTemporaryDirectory, "kodex-cli-mcp-diag-${Random.nextLong()}")
        val client = mockOpenAiClient {
            listModels { OpenAiResult.Success(ModelsResponse(emptyList())) }
        }
        fun serverState(
            name: String,
            enabled: Boolean = true,
            connection: McpClientState? = McpClientState.Healthy,
            authentication: McpAuthenticationState = McpAuthenticationState.NotConfigured,
            toolCount: Int = 0,
        ): McpManagedServerState = McpManagedServerState(
            serverName = name,
            transport = McpTransportKind.Stdio,
            enabled = enabled,
            authentication = authentication,
            connection = connection,
            toolCount = toolCount,
        )
        try {
            // 1. Backend ensure failure is surfaced directly rather than collapsing into extra arguments.
            val backendErrors = mutableListOf<String>()
            assertEquals(
                1,
                dispatchCliWithToolRpc(
                    arguments = listOf("chrome-devtools", "list_pages"),
                    defaultHome = root,
                    output = { msg, err ->
                        assertTrue(err)
                        backendErrors += msg
                    },
                    ensure = { error("Backend startup failed in test.") },
                    openServices = { _, _, _ -> error("Must not open RPC on ensure failure") },
                ) { _, _ -> error("Must not execute on ensure failure") },
            )
            assertContains(backendErrors.single(), "Backend startup failed in test.")
            assertFalse(backendErrors.single().contains("got unexpected extra arguments"))

            withBackendServices(
                home = root,
                codexHome = Path(root, "codex"),
                agentsHome = Path(root, "agents"),
                defaults = defaultBackendSettings().copy(sessionTitle = SessionTitleSettings(false)),
                createClient = { client },
            ) { backend ->
                withInMemoryRpc(backend::register) { baseRaw ->
                    val baseServices = RpcServices(RestoringRpcClient(baseRaw))
                    val publishedTool = McpCatalogTool(
                        serverName = "chrome-devtools",
                        serverInstructions = "Chrome DevTools MCP.",
                        toolName = "list_pages",
                        description = "List browser pages.",
                        inputSchema = ObjectPropertyDefinition(),
                    )
                    val serversFlow = MutableStateFlow(
                        listOf(
                            serverState("chrome-devtools", enabled = true, connection = McpClientState.Connecting),
                            serverState("disabled-srv", enabled = false, connection = null),
                            serverState("failed-srv", enabled = true, connection = McpClientState.Failed(McpClientFailureReason.Transport)),
                            serverState("oauth-srv", enabled = true, connection = McpClientState.AuthenticationBlocked, authentication = McpAuthenticationState.LoginRequired),
                            serverState("stuck-srv", enabled = true, connection = McpClientState.Connecting),
                            serverState("empty-srv", enabled = true, connection = McpClientState.Healthy, toolCount = 0),
                        ),
                    )
                    var publishedTools = emptyList<McpCatalogTool>()
                    val customGlobal = object : GlobalRpc by baseServices.global {
                        override suspend fun getMcpServers(): List<McpManagedServerState> = serversFlow.value
                        override fun getMcpServersFlow(): Flow<List<McpManagedServerState>> = serversFlow
                        override suspend fun listMcpTools(): List<McpCatalogTool> = publishedTools
                        override suspend fun callMcpTool(
                            serverName: String,
                            toolName: String,
                            arguments: JsonObject,
                        ): CallToolResult = CallToolResult(
                            content = listOf(
                                kotlinx.serialization.json.buildJsonObject {
                                    put("type", kotlinx.serialization.json.JsonPrimitive("text"))
                                    put("text", kotlinx.serialization.json.JsonPrimitive("pages-ok"))
                                },
                            ),
                        )
                    }
                    withInMemoryRpc(registerServices = {
                        registerService<GlobalRpc> { customGlobal }
                        registerService<AgentRuntimeRpc> { baseServices.runtime }
                    }) { mcpRaw ->
                        val mcpServices = RpcServices(RestoringRpcClient(mcpRaw))

                        suspend fun runDynamic(args: List<String>): Pair<Int, List<String>> {
                            val errors = mutableListOf<String>()
                            val code = dispatchCliWithToolRpc(
                                arguments = args,
                                defaultHome = root,
                                output = { msg, err ->
                                    assertTrue(err, "Expected stderr for $args: $msg")
                                    errors += msg
                                },
                                ensure = { endpoint },
                                mcpConnectTimeout = 120.milliseconds,
                                openServices = { _, _, use -> use(mcpServices) },
                            ) { _, _ -> error("Unexpected leaf execution for $args") }
                            return code to errors
                        }

                        // 2. Cold start: chrome-devtools starts Connecting and transitions to Healthy while CLI waits.
                        val stdout = mutableListOf<String>()
                        val readyCode = kotlinx.coroutines.coroutineScope {
                            val transition = launch {
                                delay(30.milliseconds)
                                publishedTools = listOf(publishedTool)
                                serversFlow.value = serversFlow.value.map {
                                    if (it.serverName == "chrome-devtools") {
                                        it.copy(connection = McpClientState.Healthy, toolCount = 1)
                                    } else {
                                        it
                                    }
                                }
                            }
                            try {
                                dispatchCliWithToolRpc(
                                    arguments = listOf("chrome-devtools", "list_pages"),
                                    defaultHome = root,
                                    output = { msg, _ -> error("Unexpected diagnostic output: $msg") },
                                    ensure = { endpoint },
                                    mcpConnectTimeout = 2_000.milliseconds,
                                    openServices = { _, _, use -> use(mcpServices) },
                                    executeTool = { toolLeaf, services ->
                                        executeCliToolCommand(toolLeaf, services, writeStdout = stdout::add)
                                    },
                                ) { leaf, invokeTool ->
                                    invokeTool(assertIs<KodexToolLeafCommand>(leaf))
                                }
                            } finally {
                                transition.join()
                            }
                        }
                        assertEquals(0, readyCode)
                        assertContains(stdout.single(), "pages-ok")

                        // 3. Unconfigured server lists available servers and never reports extra arguments.
                        val (missingCode, missingErr) = runDynamic(listOf("unknown-srv", "ping"))
                        assertEquals(1, missingCode)
                        assertContains(missingErr.single(), "MCP server 'unknown-srv' is not configured")
                        assertContains(missingErr.single(), "chrome-devtools")

                        // 4. Disabled server hints `kodex mcp enable`.
                        val (disabledCode, disabledErr) = runDynamic(listOf("disabled-srv", "ping"))
                        assertEquals(1, disabledCode)
                        assertContains(disabledErr.single(), "disabled")
                        assertContains(disabledErr.single(), "kodex mcp enable disabled-srv")

                        // 5. Failed server includes reason and hints reconnect.
                        val (failedCode, failedErr) = runDynamic(listOf("failed-srv", "ping"))
                        assertEquals(1, failedCode)
                        assertContains(failedErr.single(), "failed to connect")
                        assertContains(failedErr.single(), "Transport")
                        assertContains(failedErr.single(), "kodex mcp reconnect failed-srv")

                        // 6. Auth-blocked server explains authentication requirement.
                        val (oauthCode, oauthErr) = runDynamic(listOf("oauth-srv", "ping"))
                        assertEquals(1, oauthCode)
                        assertContains(oauthErr.single(), "requires authentication")
                        assertContains(oauthErr.single(), "login required")

                        // 7. Timed-out Connecting server explains it is still connecting.
                        val (stuckCode, stuckErr) = runDynamic(listOf("stuck-srv", "ping"))
                        assertEquals(1, stuckCode)
                        assertContains(stuckErr.single(), "still connecting")

                        // 8. Healthy server with 0 tools explains it publishes no tools.
                        val (emptyCode, emptyErr) = runDynamic(listOf("empty-srv", "ping"))
                        assertEquals(1, emptyCode)
                        assertContains(emptyErr.single(), "publishes no tools")
                    }
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
                    val rootId = services.global.getRootAgentId(sessionId)
                    val envMap = mapOf(
                        KodexAgentIdEnvironmentVariable to rootId.toString(),
                        KodexHomeEnvironmentVariable to root.toString(),
                    )
                    val fixedNow = Instant.parse("2099-01-01T00:00:00Z")

                    suspend fun runCli(args: List<String>, stdinText: String = ""): List<String> {
                        val stdout = mutableListOf<String>()
                        val exitCode = dispatchCli(
                            arguments = args,
                            output = { msg, _ -> error("Unexpected parser output: $msg") },
                        ) { leaf ->
                            executeCliToolCommand(
                                command = assertIs<KodexToolLeafCommand>(leaf),
                                services = services,
                                env = envMap::get,
                                readStdin = { stdinText },
                                writeStdout = stdout::add,
                                now = { fixedNow },
                            )
                        }
                        assertEquals(0, exitCode)
                        return stdout
                    }

                    suspend fun query(args: List<String>): CliOperationEnvelope =
                        CliToolJson.decodeFromString(CliOperationEnvelope.serializer(), runCli(args).single()).also {
                            assertTrue(it.ok)
                        }

                    val archived = services.global.createSession(root)
                    services.global.archiveSession(archived)
                    val expectedCatalog = services.global.getSessionCatalog(includeArchived = true)
                    val catalogResult = checkNotNull(query(listOf("session", "list")).result)
                    val list = CliToolJson.decodeFromJsonElement(CliSessionCatalogOutput.serializer(), catalogResult).sessions
                    val row = catalogResult.jsonObject.getValue("sessions").jsonArray.single().jsonObject
                    assertEquals(listOf(sessionId), list.map { it.sessionId })
                    assertEquals("Session $sessionId", row.getValue("name").jsonPrimitive.content)
                    assertFalse("thread_name" in row)
                    assertFalse("threadName" in row)
                    val fullList = CliToolJson.decodeFromJsonElement(
                        CliSessionCatalogOutput.serializer(),
                        checkNotNull(query(listOf("session", "list", "--include-archived")).result),
                    ).sessions
                    assertEquals(expectedCatalog.map { it.sessionIndex }, fullList.map { it.sessionId })
                    assertEquals(expectedCatalog.map { it.isActive to it.running }, fullList.map { it.isActive to it.running })
                    assertEquals(expectedCatalog.map { it.rootAgentId }, fullList.map { it.rootAgentId })
                    assertTrue(fullList.single { it.sessionId == archived }.archived)

                    val statusResult = checkNotNull(query(listOf("agent", "status", "--agent", rootId.toString())).result).jsonObject
                    val status = CliToolJson.decodeFromJsonElement(CliAgentStatusOutput.serializer(), statusResult)
                    assertEquals(rootId, status.agentId)
                    assertEquals("Session $sessionId", statusResult.getValue("name").jsonPrimitive.content)
                    assertFalse("thread_name" in statusResult)
                    assertFalse("threadName" in statusResult)
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
                        runCli(listOf("ask", "get", "1", "--agent", rootId.toString())).single(),
                    )
                    assertEquals(createdAsk, askSingle)

                    // 3. `kodex schedule create`, `kodex schedule list`, `kodex schedule delete`
                    val createdSchedule = CliToolJson.decodeFromString(
                        ScheduleRecord.serializer(),
                        runCli(listOf("schedule", "create", "-d", "Check build", "--delay-seconds", "60")).single(),
                    )
                    assertEquals(rootId, createdSchedule.agentId)
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
                    assertTrue(toolLines.any { it.startsWith("kodex session list\t") })
                    assertTrue(toolLines.any { it.startsWith("kodex agent status\t") })
                    assertTrue(toolLines.any { it.startsWith("kodex agent fork\t") })
                    assertTrue(toolLines.none { it.startsWith("kodex session fork\t") })
                    assertTrue(toolLines.none { it.startsWith("kodex session status\t") })
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
