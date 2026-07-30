package io.github.stream29.kodex.agentstate.impl

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentcontext.contract.AgentContextSettings
import io.github.stream29.kodex.agentcontext.prefix.render.render as renderCollaborationMode
import io.github.stream29.kodex.agentcontext.prefix.render.renderMultiAgentMode
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.mcp.contract.McpService
import io.github.stream29.kodex.mcp.contract.McpTool
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.CompactionPhase
import io.github.stream29.kodex.openai.CompactionReason
import io.github.stream29.kodex.openai.CompactionTrigger
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.ModeKind
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.PlanItemArg
import io.github.stream29.kodex.openai.RemoteCompactionV2Response
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesApiRequest
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.StepStatus
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
import kotlinx.coroutines.flow.toList
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
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
                agent.requestResponseApi().toList()

                val input = requests.single().input
                assertEquals(collaborationMessage(ModeKind.Default), input[0])
                assertEquals(multiAgentMessage(), input[1])
                assertTrue(
                    input.contextText().contains(
                        "user instructions\n\n--- project-doc ---\n\nproject instructions",
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

        test("projects mode and discovered skills without plan or goal state") {
            val fixture = contextFixture("mode-and-skills")
            try {
                fixture.writeUserAgentsMd("agent instructions")
                fixture.writeSkill("test-skill", "test description")
                val storage = InMemoryKodexAgentStorage(
                    settings(
                        cwd = fixture.project,
                        collaborationMode = ModeKind.Plan,
                        plan = UpdatePlanArgs(
                            explanation = "Keep this plan out of the model prompt.",
                            plan = listOf(
                                PlanItemArg("Complete the implementation.", StepStatus.InProgress),
                            ),
                        ),
                        goal = ThreadGoal(
                            objective = "Finish the implementation.",
                            status = ThreadGoalStatus.Active,
                        ),
                    ),
                )
                val requests = mutableListOf<ResponsesApiRequest>()
                val agent = KodexAgentState(
                    client = recordingClient(requests),
                    storage = storage,
                    contextSettings = fixture.settings,
                    mcpService = ProjectionMcpService(),
                )
                val user = userMessage("use a tool")

                agent.appendUserMessage(user.content)
                agent.requestResponseApi().toList()

                val input = requests.single().input
                val rendered = input.text()
                assertEquals(collaborationMessage(ModeKind.Plan), input[0])
                assertEquals(multiAgentMessage(), input[1])
                assertTrue(rendered.contains("test-skill"))
                assertTrue(rendered.contains("test description"))
                assertTrue(rendered.contains("agent instructions"))
                assertTrue(!rendered.contains("Keep this plan out of the model prompt."))
                assertTrue(!rendered.contains("Finish the implementation."))
                assertEquals(user, input.last())
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
                agent.requestResponseApi().toList()
                fixture.writeUserAgentsMd("second instructions")
                fixture.settings.value = ProjectionContextSettings(
                    codexHome = fixture.codexHome,
                    shell = Shell(ShellType.Zsh, Path("/bin/zsh")),
                )
                agent.updateSettings(initialSettings.copy(cwd = updatedProject))
                mcpService.tools.value = listOf(ProjectionMcpTool)
                agent.requestResponseApi().toList()

                assertTrue(requests[0].input.contextText().contains("first instructions"))
                assertTrue(requests[0].input.contextText().contains("<shell>bash</shell>"))
                assertTrue(requests[1].input.contextText().contains("second instructions"))
                assertTrue(requests[1].input.contextText().contains("updated project instructions"))
                assertTrue(requests[1].input.contextText().contains("<shell>zsh</shell>"))
                val firstToolSearchSpec = assertIs<ToolSpec.ToolSearch>(requests[0].tools.last())
                val secondToolSearchSpec = assertIs<ToolSpec.ToolSearch>(requests[1].tools.last())
                assertTrue(firstToolSearchSpec.description.contains("None currently enabled."))
                assertTrue(secondToolSearchSpec.description.contains("- projection: Projection tools."))
            } finally {
                deleteRecursively(fixture.root)
            }
        }

        test("remote compaction excludes transient context") {
            val fixture = contextFixture("compaction")
            try {
                fixture.writeUserAgentsMd("must not be compacted")
                val storage = InMemoryKodexAgentStorage(settings(cwd = fixture.project))
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
                assertEquals(2, compactIndex)
                assertEquals(StableCleanEvent.UserMessage(user.content), storage.stable[1])
                assertEquals(StableCleanEvent.ContextCompaction, storage.stable[compactIndex])
                assertEquals(
                    listOf(StableCleanEvent.UserMessage(user.content)),
                    storage.compaction[compactIndex].prefix,
                )
                assertEquals(
                    ResponseItem.Compaction(encryptedContent = "compacted"),
                    storage.compaction[compactIndex].compaction,
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

private fun settings(
    cwd: Path,
    collaborationMode: ModeKind = ModeKind.Default,
    plan: UpdatePlanArgs = UpdatePlanArgs(plan = emptyList()),
    goal: ThreadGoal? = null,
): KodexAgentSettings =
    KodexAgentSettings(
        model = OpenAiModelId("test-model"),
        cwd = cwd,
        collaborationMode = collaborationMode,
        plan = plan,
        goal = goal,
    )

private fun userMessage(text: String): ResponseItem.Message =
    message(MessageRole.User, text)

private fun collaborationMessage(mode: ModeKind): ResponseItem.Message =
    message(MessageRole.Developer, mode.renderCollaborationMode())

private fun multiAgentMessage(): ResponseItem.Message =
    message(
        MessageRole.Developer,
        KodexAgentSettings(OpenAiModelId("test-model"))
            .reasoning.effort.renderMultiAgentMode(),
    )

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
    val codexHome: Path,
    val project: Path,
    val settings: MutableStateFlow<AgentContextSettings>,
) {
    suspend fun writeUserAgentsMd(text: String) {
        SystemCoroutineFileSystem.writeString(Path(codexHome, "AGENTS.md"), text)
    }

    suspend fun writeProjectAgentsMd(text: String) {
        SystemCoroutineFileSystem.writeString(Path(project, "AGENTS.md"), text)
    }

    suspend fun writeSkill(name: String, description: String) {
        val directory = Path(codexHome, "skills/$name")
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
    val codexHome = Path(root, "codex-home")
    val project = Path(root, "project")
    SystemCoroutineFileSystem.createDirectories(codexHome)
    SystemCoroutineFileSystem.createDirectories(Path(project, ".git"))
    return ContextFixture(
        root = root,
        codexHome = codexHome,
        project = project,
        settings = MutableStateFlow(
            ProjectionContextSettings(
                codexHome = codexHome,
                shell = Shell(ShellType.Bash, Path("/bin/bash")),
            ),
        ),
    )
}

private data class ProjectionContextSettings(
    override val codexHome: Path,
    override val shell: Shell,
) : AgentContextSettings

private class ProjectionMcpService(
    initialTools: List<McpTool> = emptyList(),
) : McpService {
    override val tools = MutableStateFlow(initialTools.toList())

    override suspend fun refresh() = Unit

    override fun close() = Unit
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
