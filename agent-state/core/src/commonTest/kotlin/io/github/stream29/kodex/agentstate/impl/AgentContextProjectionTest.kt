package io.github.stream29.kodex.agentstate.impl

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentcontext.contract.AgentContextSettings
import io.github.stream29.kodex.agentcontext.contract.AgentContextSourceSettings
import io.github.stream29.kodex.agentstate.tool.visibleToolSpecs
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.contract.getCommitAtOrBefore
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.openai.CompactionPhase
import io.github.stream29.kodex.openai.CompactionReason
import io.github.stream29.kodex.openai.CompactionTrigger
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.RemoteCompactionV2Response
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesApiRequest
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.ThreadGoal
import io.github.stream29.kodex.openai.UpdatePlanArgs
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
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
import kotlin.test.assertTrue

val agentContextProjectionTest by testSuite {
    testFixture {
        testSuiteCoroutineScope.supervisorChildScope()
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
        test("projects filesystem context and tool CLI instructions without persisting them") {
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
                )
                val user = userMessage("hello")

                assertEquals(1, agent.appendUserMessage(user.content))
                agent.requestResponseApi()

                val input = requests.single().input
                assertTrue(input.developerText().contains("<tool_cli_instructions>"))
                assertTrue(input.developerText().contains("kodex ask (create | list | get)"))
                assertTrue(
                    input.contextText().contains(
                        "user instructions\n\nkodex instructions\n\n--- project-doc ---\n\nproject instructions",
                    ),
                )
                assertEquals(user, input.last())
                assertEquals(StableUserMessage(user.content), storage.getIndexEntryAt(1))
                assertEquals(1, storage.getCommitAtOrBefore(Int.MAX_VALUE, AgentCommitType.IndexTypes)?.index)
                assertEquals(1, storage.latestIndex())
            } finally {
                deleteRecursively(fixture.root)
            }
        }

        test("samples current context and RequestUserInputMode for every response request while keeping static 5 tools") {
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
                val agent = KodexAgentState(
                    client = recordingClient(requests),
                    storage = storage,
                    contextSettings = fixture.settings,
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
                agent.requestResponseApi()

                assertTrue(requests[0].input.contextText().contains("first instructions"))
                assertTrue(requests[0].input.contextText().contains("<shell>bash</shell>"))
                assertTrue(requests[0].input.developerText().contains("kodex ask (create | list | get)"))
                assertTrue(requests[1].input.contextText().contains("second instructions"))
                assertTrue(requests[1].input.contextText().contains("updated project instructions"))
                assertTrue(requests[1].input.contextText().contains("<shell>zsh</shell>"))
                assertFalse(requests[1].input.developerText().contains("kodex ask (create | list | get)"))
                assertEquals(visibleToolSpecs(), requests[0].tools)
                assertEquals(visibleToolSpecs(), requests[1].tools)
            } finally {
                deleteRecursively(fixture.root)
            }
        }

        test("remote compaction excludes transient context and uses static 5 tools") {
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
                        createRemoteCompactionV2Response { request ->
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
                assertEquals(visibleToolSpecs(), compactionRequests.single().tools)
                assertEquals(2, compactIndex)
                assertEquals(StableUserMessage(user.content), storage.getIndexEntryAt(1))
                assertEquals(
                    StableContextCompaction(
                        encryptedContent = "compacted",
                        retainedItems = listOf(StableUserMessage(user.content)),
                    ),
                    storage.getIndexEntryAt(compactIndex),
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

private fun message(role: MessageRole, vararg sections: String): ResponseItem.Message =
    ResponseItem.Message(
        role = role,
        content = sections.map(ContentItem::InputText),
    )

private fun List<ResponseItem>.developerText(): String =
    filterIsInstance<ResponseItem.Message>()
        .filter { message -> message.role == MessageRole.Developer }
        .flatMap(ResponseItem.Message::content)
        .filterIsInstance<ContentItem.InputText>()
        .joinToString(separator = "\n", transform = ContentItem.InputText::text)

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
    override val codexHome: Path = Path(kodexHome, "codex-home"),
    override val sources: AgentContextSourceSettings = AgentContextSourceSettings(),
) : AgentContextSettings

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { child -> deleteRecursively(child) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
