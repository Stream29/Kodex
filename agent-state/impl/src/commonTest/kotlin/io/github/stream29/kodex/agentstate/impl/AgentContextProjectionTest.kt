package io.github.stream29.kodex.agentstate.impl

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentcontext.contract.AgentContextSettings
import io.github.stream29.kodex.agentcontext.prefix.render.renderPlanningInstructions
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.mcp.contract.McpClient
import io.github.stream29.kodex.mcp.contract.McpClientState
import io.github.stream29.kodex.mcp.contract.McpAuthenticationState
import io.github.stream29.kodex.mcp.contract.McpService
import io.github.stream29.kodex.mcp.contract.McpTool
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.CompactionPhase
import io.github.stream29.kodex.openai.CompactionReason
import io.github.stream29.kodex.openai.CompactionTrigger
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.RemoteCompactionV2Response
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesApiRequest
import io.github.stream29.kodex.openai.ResponsesApiTool
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ThreadGoal
import io.github.stream29.kodex.openai.ThreadGoalStatus
import io.github.stream29.kodex.openai.ToolSpec
import io.github.stream29.kodex.openai.UpdatePlanArgs
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.tool.currenttime.CurrentTimeTools
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

val agentContextProjectionTest by testSuite {
    testFixture {
        testSuiteCoroutineScope.supervisorChildScope()
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
        test("projects filesystem context without persisting it") {
            val fixture = contextFixture("projection")
            try {
                fixture.writeUserAgentsMd("user instructions")
                fixture.writeKodexAgentsMd("kodex instructions")
                fixture.writeProjectAgentsMd("project instructions")
                val storage = InMemoryKodexAgentStorage(settings(cwd = fixture.project))
                val requests = mutableListOf<ResponsesApiRequest>()
                val agent = KodexAgentState(
                    client = recordingClient(requests),
                    storage = storage,
                    contextSettings = fixture.settings,
                    mcpService = ProjectionMcpService(),
                )
                val user = userMessage("hello")

                assertEquals(1, agent.appendUserMessage(user.content))
                agent.requestResponseApi()

                val input = requests.single().input
                assertEquals(planningMessage(), input[0])
                assertTrue(
                    input.contextText().contains(
                        "user instructions\n\nkodex instructions\n\n--- project-doc ---\n\nproject instructions",
                    ),
                )
                assertEquals(user, input.last())
                assertEquals(StableCleanEvent.UserMessage(user.content), storage.stable[1])
                assertEquals(1, storage.stable.latestIndex())
                assertEquals(1, storage.latestIndex())
            } finally {
                deleteRecursively(fixture.root)
            }
        }

        test("samples current context and tool-search state for every response request") {
            val fixture = contextFixture("live-state")
            try {
                fixture.writeUserAgentsMd("first instructions")
                val initialSettings = settings(cwd = fixture.project)
                val updatedProject = Path(fixture.root, "updated")
                SystemCoroutineFileSystem.createDirectories(Path(updatedProject, ".git"))
                SystemCoroutineFileSystem.writeString(
                    Path(updatedProject, "AGENTS.md"),
                    "updated project instructions",
                )
                val storage = InMemoryKodexAgentStorage(initialSettings)
                val requests = mutableListOf<ResponsesApiRequest>()
                val mcpService = ProjectionMcpService()
                val agent = KodexAgentState(
                    client = recordingClient(requests),
                    storage = storage,
                    contextSettings = fixture.settings,
                    mcpService = mcpService,
                )
                val user = userMessage("continue")

                agent.appendUserMessage(user.content)
                agent.requestResponseApi()
                fixture.writeUserAgentsMd("second instructions")
                fixture.settings.value = ProjectionContextSettings(
                    agentsHome = fixture.agentsHome,
                    kodexHome = fixture.kodexHome,
                    shell = Shell(ShellType.Zsh, Path("/bin/zsh")),
                )
                agent.updateSettings(
                    initialSettings.copy(
                        cwd = updatedProject,
                        requestUserInputMode = RequestUserInputMode.NoQuestion,
                    ),
                )
                mcpService.clients.value = mapOf("projection" to ProjectionMcpClient)
                agent.requestResponseApi()

                assertTrue(requests[0].input.contextText().contains("first instructions"))
                assertTrue(requests[0].input.contextText().contains("<shell>bash</shell>"))
                assertTrue(requests[1].input.contextText().contains("second instructions"))
                assertTrue(requests[1].input.contextText().contains("updated project instructions"))
                assertTrue(requests[1].input.contextText().contains("<shell>zsh</shell>"))
                val firstToolSearchSpec = assertIs<ToolSpec.ToolSearch>(requests[0].tools.last())
                val secondToolSearchSpec = assertIs<ToolSpec.ToolSearch>(requests[1].tools.last())
                assertTrue(firstToolSearchSpec.description.contains("None currently enabled."))
                assertTrue(secondToolSearchSpec.description.contains("- projection: Projection tools."))
                assertTrue(requests[0].hasRequestUserInputTool())
                assertFalse(requests[1].hasRequestUserInputTool())
            } finally {
                deleteRecursively(fixture.root)
            }
        }

        test("remote compaction excludes transient context") {
            val fixture = contextFixture("compaction")
            try {
                fixture.writeUserAgentsMd("must not be compacted")
                val storage = InMemoryKodexAgentStorage(
                    settings(cwd = fixture.project).copy(
                        requestUserInputMode = RequestUserInputMode.NoQuestion,
                    ),
                )
                val compactionRequests = mutableListOf<ResponsesApiRequest>()
                val agent = KodexAgentState(
                    client = mockOpenAiClient {
                        createRemoteCompactionV2Response { request, _, _, _ ->
                            compactionRequests += request
                            RemoteCompactionV2Response(
                                compactionOutput = ResponseItem.Compaction(
                                    encryptedContent = "compacted",
                                ),
                                completedResponse = null,
                            )
                        }
                    },
                    storage = storage,
                    contextSettings = fixture.settings,
                    mcpService = ProjectionMcpService(listOf(ProjectionMcpTool)),
                )
                val user = userMessage("compact this")

                agent.appendUserMessage(user.content)
                val compactIndex = agent.compact(
                    trigger = CompactionTrigger.Auto,
                    reason = CompactionReason.ContextLimit,
                    phase = CompactionPhase.PreTurn,
                )

                assertEquals(
                    listOf(user, ResponseItem.CompactionTrigger),
                    compactionRequests.single().input,
                )
                val toolSearchSpec =
                    assertIs<ToolSpec.ToolSearch>(compactionRequests.single().tools.last())
                assertTrue(toolSearchSpec.description.contains("- projection: Projection tools."))
                assertFalse(compactionRequests.single().hasRequestUserInputTool())
                assertEquals(2, compactIndex)
                assertEquals(StableCleanEvent.UserMessage(user.content), storage.stable[1])
                assertEquals(
                    StableCleanEvent.ContextCompaction(encryptedContent = "compacted"),
                    storage.stable[compactIndex],
                )
                assertEquals(
                    listOf(StableCleanEvent.UserMessage(user.content)),
                    storage.compaction[compactIndex].prefix,
                )
            } finally {
                deleteRecursively(fixture.root)
            }
        }
    }
}

private fun recordingClient(
    requests: MutableList<ResponsesApiRequest>,
) = mockOpenAiClient {
    createResponse { request ->
        requests += request
        flowOf(ResponsesStreamEvent.Completed(Response(id = "response_${requests.size}")))
    }
}

private fun ResponsesApiRequest.hasRequestUserInputTool(): Boolean =
    tools.filterIsInstance<ResponsesApiTool>()
        .any { tool -> tool.name == "request_user_input" }

private fun settings(
    cwd: Path,
    plan: UpdatePlanArgs = UpdatePlanArgs(plan = emptyList()),
    goal: ThreadGoal? = null,
): KodexAgentSettings =
    KodexAgentSettings(
        model = OpenAiModelId("test-model"),
        cwd = cwd,
        plan = plan,
        goal = goal,
    )

private fun userMessage(text: String): ResponseItem.Message =
    message(MessageRole.User, text)

private fun planningMessage(): ResponseItem.Message =
    message(MessageRole.Developer, renderPlanningInstructions())

private fun message(role: MessageRole, vararg sections: String): ResponseItem.Message =
    ResponseItem.Message(
        role = role,
        content = sections.map(ContentItem::InputText),
    )

private fun List<ResponseItem>.contextText(): String =
    filterIsInstance<ResponseItem.Message>()
        .first { message ->
            message.role == MessageRole.User &&
                message.content.filterIsInstance<ContentItem.InputText>()
                    .any { content -> "<environment_context>" in content.text }
        }
        .content
        .filterIsInstance<ContentItem.InputText>()
        .joinToString(separator = "\n", transform = ContentItem.InputText::text)

private fun List<ResponseItem>.text(): String =
    filterIsInstance<ResponseItem.Message>()
        .flatMap(ResponseItem.Message::content)
        .filterIsInstance<ContentItem.InputText>()
        .joinToString(separator = "\n", transform = ContentItem.InputText::text)

private class ContextFixture(
    val root: Path,
    val agentsHome: Path,
    val kodexHome: Path,
    val project: Path,
    val settings: MutableStateFlow<AgentContextSettings>,
) {
    suspend fun writeUserAgentsMd(text: String) {
        SystemCoroutineFileSystem.writeString(Path(agentsHome, "AGENTS.md"), text)
    }

    suspend fun writeProjectAgentsMd(text: String) {
        SystemCoroutineFileSystem.writeString(Path(project, "AGENTS.md"), text)
    }

    suspend fun writeKodexAgentsMd(text: String) {
        SystemCoroutineFileSystem.writeString(Path(kodexHome, "AGENTS.md"), text)
    }

    suspend fun writeSkill(name: String, description: String) {
        val directory = Path(agentsHome, "skills/$name")
        SystemCoroutineFileSystem.createDirectories(directory)
        SystemCoroutineFileSystem.writeString(
            Path(directory, "SKILL.md"),
            """
            ---
            name: $name
            description: $description
            ---
            Skill instructions.
            """.trimIndent(),
        )
    }
}

private suspend fun contextFixture(name: String): ContextFixture {
    val root = Path(SystemTemporaryDirectory, "kodex-state-context-$name-${Random.nextLong()}")
    val agentsHome = Path(root, "agents-home")
    val kodexHome = Path(root, "kodex-home")
    val project = Path(root, "project")
    SystemCoroutineFileSystem.createDirectories(agentsHome)
    SystemCoroutineFileSystem.createDirectories(kodexHome)
    SystemCoroutineFileSystem.createDirectories(Path(project, ".git"))
    return ContextFixture(
        root = root,
        agentsHome = agentsHome,
        kodexHome = kodexHome,
        project = project,
        settings = MutableStateFlow(
            ProjectionContextSettings(
                agentsHome = agentsHome,
                kodexHome = kodexHome,
                shell = Shell(ShellType.Bash, Path("/bin/bash")),
            ),
        ),
    )
}

private data class ProjectionContextSettings(
    override val agentsHome: Path,
    override val kodexHome: Path,
    override val shell: Shell,
) : AgentContextSettings

private class ProjectionMcpService(
    initialTools: List<McpTool> = emptyList(),
) : McpService {
    override val clients: MutableStateFlow<Map<String, McpClient>> = MutableStateFlow(
        initialTools
            .groupBy(McpTool::serverName)
            .mapValues { (serverName, tools) ->
                FixedProjectionMcpClient(serverName, tools)
            },
    )
    override val authentication = MutableStateFlow<Map<String, McpAuthenticationState>>(emptyMap())

    override suspend fun invalidate(serverName: String) = Unit

    override suspend fun refresh() = Unit

    override fun close() = Unit
}

private class FixedProjectionMcpClient(
    override val serverName: String,
    private val tools: List<McpTool>,
) : McpClient {
    override val state = MutableStateFlow<McpClientState>(McpClientState.Healthy)

    override fun listTools(): List<McpTool> = tools

    override suspend fun reconnect() = Unit
}

private object ProjectionMcpClient : McpClient {
    override val serverName: String = "projection"
    override val state = MutableStateFlow<McpClientState>(McpClientState.Healthy)

    override fun listTools(): List<McpTool> = listOf(ProjectionMcpTool)

    override suspend fun reconnect() = Unit
}

private object ProjectionMcpTool : McpTool {
    override val serverName: String = "projection"
    override val serverInstructions: String = "Projection tools."
    override val spec: ToolSpec = CurrentTimeTools.spec

    override suspend fun handle(pending: PendingToolEvent): StableCleanEvent.CompletedTool =
        error("Projection tests never execute MCP tools.")

    override fun close() = Unit
}

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { child -> deleteRecursively(child) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
