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
import io.github.stream29.kodex.mcp.contract.McpCatalogTool
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
import io.github.stream29.kodex.rpc.server.defaultBackendSettings
import io.github.stream29.kodex.rpc.server.withBackendServices
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputAnswer
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.tool.unifiedexec.KodexAgentIdEnvironmentVariable
import io.github.stream29.kodex.tool.unifiedexec.KodexHomeEnvironmentVariable
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlin.random.Random
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.awaitCancellation
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
                                expect.copy(cwd = unrelatedDirectory, threadName = concurrentTitle ?: expect.threadName)))
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
                        assertEquals(expectedExit, exit, lines.toString())
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
                assertTrue(cli(listOf("session", "rename", "--session", "$targetSession", "--name", " first title "), "", 0).ok)
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
                assertEquals("frontend title", archived.threadName)
                assertTrue(cli(listOf("session", "unarchive", "--session", "$targetSession"), "", 0).ok)
                assertFalse(backend.global.getSessionCatalog(true).single { it.sessionIndex == targetSession }.archived)
                assertTrue(cli(listOf("agent", "stop", "--agent", "$target"), "", 0).ok)
                backend.runtime.getRunningTurnFlow(target).first { !it }
                val index = backend.runtime.getLatestIndex(target)
                assertEquals(index.toString(), cli(listOf("agent", "clear-pending", "--agent", "$target"), "", 0).result("latest_index"))
                val forked = cli(listOf("session", "fork", "--session", "$targetSession"), "", 0)
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
            discover = { error("fixture-only upstream details") },
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
                            discover = { endpoint },
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
                            discover = { endpoint },
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
                    val list = CliToolJson.decodeFromJsonElement(
                        CliSessionCatalogOutput.serializer(), checkNotNull(query(listOf("session", "list")).result),
                    ).sessions
                    assertEquals(listOf(sessionId), list.map { it.sessionId })
                    val fullList = CliToolJson.decodeFromJsonElement(
                        CliSessionCatalogOutput.serializer(),
                        checkNotNull(query(listOf("session", "list", "--include-archived")).result),
                    ).sessions
                    assertEquals(expectedCatalog.map { it.sessionIndex }, fullList.map { it.sessionId })
                    assertEquals(expectedCatalog.map { it.isActive to it.running }, fullList.map { it.isActive to it.running })
                    assertEquals(expectedCatalog.map { it.rootAgentId }, fullList.map { it.rootAgentId })
                    assertTrue(fullList.single { it.sessionId == archived }.archived)

                    val status = CliToolJson.decodeFromJsonElement(
                        CliAgentStatusOutput.serializer(),
                        checkNotNull(query(listOf("agent", "status", "--agent", rootId.toString())).result),
                    )
                    assertEquals(rootId, status.agentId)
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
