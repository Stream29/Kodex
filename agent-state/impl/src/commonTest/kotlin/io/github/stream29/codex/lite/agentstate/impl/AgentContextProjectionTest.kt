package io.github.stream29.codex.lite.agentstate.impl

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentcontext.contract.AgentContextInjection
import io.github.stream29.codex.lite.agentcontext.contract.AgentEnvironment
import io.github.stream29.codex.lite.agentcontext.contract.AgentsMdInstruction
import io.github.stream29.codex.lite.agentcontext.contract.AvailableSkill
import io.github.stream29.codex.lite.agentcontext.contract.DeveloperInstructions
import io.github.stream29.codex.lite.agentcontext.contract.EnvironmentContext
import io.github.stream29.codex.lite.agentstorage.contract.latestIndex
import io.github.stream29.codex.lite.agentstorage.inmemory.InMemoryCodexAgentStorage
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Phase
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Reason
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Request
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Response
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Trigger
import io.github.stream29.codex.lite.openai.Response
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.client.test.mockOpenAiClient
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.io.files.Path
import kotlin.test.assertEquals

val agentContextProjectionTest by testSuite {
    test("projects AGENTS.md into a request without persisting it") {
        val storage = InMemoryCodexAgentStorage(settings())
        val requests = mutableListOf<ResponsesApiRequest>()
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse { request ->
                    requests += request
                    flowOf(ResponsesStreamEvent.Completed(Response(id = "response")))
                }
            },
            storage = storage,
            contextInjection = AgentContextInjection(
                environmentContext = testEnvironmentContext,
                agentMd = listOf(
                    AgentsMdInstruction.User(
                        source = Path("/home/stream/AGENTS.md"),
                        text = "user instructions",
                    ),
                    AgentsMdInstruction.Project(
                        source = Path("/workspace/AGENTS.md"),
                        environmentId = "local",
                        cwd = Path("/workspace"),
                        text = "project instructions",
                    ),
                ),
            ),
        )
        val user = userMessage("hello")

        assertEquals(1, agent.appendUserMessage(user.content))
        agent.requestResponseApi().toList()

        assertEquals(
            listOf(
                contextualUserMessage(
                    agentMdForDirectory(
                        "user instructions\n\n--- project-doc ---\n\nproject instructions",
                        directory = "/workspace",
                    ),
                    environmentContext(),
                ),
                user,
            ),
            requests.single().input,
        )
        assertEquals(user, storage.history[1])
        assertEquals(1, storage.history.latestIndex())
        assertEquals(1, storage.latestIndex())
    }

    test("renders every context source in the request prefix") {
        val storage = InMemoryCodexAgentStorage(settings())
        val requests = mutableListOf<ResponsesApiRequest>()
        val skillCatalog = listOf(
            AvailableSkill(
                name = "test-skill",
                description = "test description",
                path = Path("/skills/test-skill/SKILL.md"),
            ),
        )
        val currentDate = LocalDate(2026, 7, 15)
        val timeZone = TimeZone.UTC
        val environment = EnvironmentContext(
            environments = listOf(
                AgentEnvironment(
                    id = "local",
                    cwd = Path("/workspace"),
                    shell = "bash",
                ),
            ),
            currentDate = currentDate,
            timeZone = timeZone,
        )
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse { request ->
                    requests += request
                    flowOf(ResponsesStreamEvent.Completed(Response(id = "response")))
                }
            },
            storage = storage,
            contextInjection = AgentContextInjection(
                environmentContext = environment,
                developerInstructions = DeveloperInstructions("developer instructions"),
                availableSkills = skillCatalog,
                agentMd = listOf(
                    AgentsMdInstruction.Internal(text = "agent instructions"),
                ),
            ),
        )
        val user = userMessage("use a tool")

        agent.appendUserMessage(user.content)
        agent.requestResponseApi().toList()

        assertEquals(
            listOf(
                developerMessage(
                    "developer instructions",
                    availableSkills(skillCatalog),
                ),
                contextualUserMessage(
                    agentMd("agent instructions"),
                    environmentContext(
                        cwd = Path("/workspace"),
                        shell = "bash",
                        currentDate = currentDate,
                        timeZone = timeZone,
                    ),
                ),
                user,
            ),
            requests.single().input,
        )
        assertEquals(user, storage.history[1])
        assertEquals(1, storage.history.latestIndex())
    }

    test("projects transient context for every response request") {
        val storage = InMemoryCodexAgentStorage(settings())
        val requests = mutableListOf<ResponsesApiRequest>()
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse { request ->
                    requests += request
                    flowOf(ResponsesStreamEvent.Completed(Response(id = "response_${requests.size}")))
                }
            },
            storage = storage,
            contextInjection = AgentContextInjection(
                environmentContext = testEnvironmentContext,
                agentMd = listOf(
                    AgentsMdInstruction.Internal(text = "agent instructions"),
                ),
            ),
        )
        val user = userMessage("continue")

        agent.appendUserMessage(user.content)
        agent.requestResponseApi().toList()
        agent.requestResponseApi().toList()

        assertEquals(
            listOf(
                listOf(contextualUserMessage(agentMd("agent instructions"), environmentContext()), user),
                listOf(contextualUserMessage(agentMd("agent instructions"), environmentContext()), user),
            ),
            requests.map(ResponsesApiRequest::input),
        )
        assertEquals(user, storage.history[1])
        assertEquals(1, storage.history.latestIndex())
    }

    test("remote compaction does not persist context injection") {
        val storage = InMemoryCodexAgentStorage(settings())
        val compactionRequests = mutableListOf<RemoteCompactionV2Request>()
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response { request ->
                    compactionRequests += request
                    RemoteCompactionV2Response(
                        compactionOutput = ResponseItem.Compaction(encryptedContent = "compacted"),
                        completedResponse = null,
                    )
                }
            },
            storage = storage,
            contextInjection = AgentContextInjection(
                environmentContext = testEnvironmentContext,
                agentMd = listOf(
                    AgentsMdInstruction.Internal(text = "agent instructions"),
                ),
            ),
        )
        val user = userMessage("compact this")

        agent.appendUserMessage(user.content)
        val compactIndex = agent.compact(
            trigger = RemoteCompactionV2Trigger.Auto,
            reason = RemoteCompactionV2Reason.ContextLimit,
            phase = RemoteCompactionV2Phase.PreTurn,
        )

        assertEquals(listOf(user), compactionRequests.single().history)
        assertEquals(2, compactIndex)
        assertEquals(user, storage.history[1])
        assertEquals(
            ResponseItem.ContextCompaction(encryptedContent = "compacted"),
            storage.history[compactIndex],
        )
    }

    test("renders raw project AGENTS.md sources per environment") {
        val storage = InMemoryCodexAgentStorage(settings())
        val requests = mutableListOf<ResponsesApiRequest>()
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse { request ->
                    requests += request
                    flowOf(ResponsesStreamEvent.Completed(Response(id = "response")))
                }
            },
            storage = storage,
            contextInjection = AgentContextInjection(
                environmentContext = testEnvironmentContext,
                agentMd = listOf(
                    AgentsMdInstruction.User(
                        source = Path("/home/stream/AGENTS.md"),
                        text = "user instructions",
                    ),
                    AgentsMdInstruction.Project(
                        source = Path("/workspace/AGENTS.md"),
                        environmentId = "local",
                        cwd = Path("/workspace"),
                        text = "workspace instructions",
                    ),
                    AgentsMdInstruction.Project(
                        source = Path("/workspace/nested/AGENTS.md"),
                        environmentId = "local",
                        cwd = Path("/workspace"),
                        text = "nested workspace instructions",
                    ),
                    AgentsMdInstruction.Internal(text = "internal instructions"),
                    AgentsMdInstruction.Project(
                        source = Path("/remote/AGENTS.md"),
                        environmentId = "remote",
                        cwd = Path("/remote"),
                        text = "remote instructions",
                    ),
                ),
            ),
        )
        val user = userMessage("hello")

        agent.appendUserMessage(user.content)
        agent.requestResponseApi().toList()

        assertEquals(
            listOf(
                contextualUserMessage(
                    agentMd(
                        """
                        user instructions

                        for `local` with root /workspace

                        workspace instructions

                        nested workspace instructions

                        internal instructions

                        for `remote` with root /remote

                        remote instructions
                        """.trimIndent(),
                    ),
                    environmentContext(),
                ),
                user,
            ),
            requests.single().input,
        )
    }
}

private val testCurrentDate: LocalDate = LocalDate(2026, 7, 15)

private val testEnvironmentContext: EnvironmentContext =
    EnvironmentContext(
        environments = listOf(
            AgentEnvironment(
                id = "local",
                cwd = Path("/workspace"),
                shell = "bash",
            ),
        ),
        currentDate = testCurrentDate,
        timeZone = TimeZone.UTC,
    )

private fun settings(): CodexAgentSettings =
    CodexAgentSettings(OpenAiModelId("test-model"))

private fun userMessage(text: String): ResponseItem.Message =
    message(MessageRole.User, text)

private fun developerMessage(vararg sections: String): ResponseItem.Message =
    message(MessageRole.Developer, *sections)

private fun contextualUserMessage(vararg sections: String): ResponseItem.Message =
    message(MessageRole.User, *sections)

private fun message(role: MessageRole, vararg sections: String): ResponseItem.Message =
    ResponseItem.Message(
        role = role,
        content = sections.map(ContentItem::InputText),
    )

private fun agentMd(contents: String): String =
    "# AGENTS.md instructions\n\n<INSTRUCTIONS>\n$contents\n</INSTRUCTIONS>"

private fun agentMdForDirectory(contents: String, directory: String): String =
    "# AGENTS.md instructions for $directory\n\n<INSTRUCTIONS>\n$contents\n</INSTRUCTIONS>"

private fun availableSkills(skills: List<AvailableSkill>): String = buildString {
    append("<skills_instructions>\n")
    append("## Skills\n")
    append("A skill is a set of local instructions stored in a `SKILL.md` file. Below is the list of skills available to the agent. Each entry includes a name, description, and path.\n")
    append("### Available skills\n")
    skills.forEach { skill ->
        append("- name: ")
        append(skill.name)
        append("\n  description: ")
        append(skill.description)
        append("\n  path: ")
        append(skill.path)
        append('\n')
    }
    append("</skills_instructions>")
}

private fun environmentContext(
    cwd: Path = Path("/workspace"),
    shell: String = "bash",
    currentDate: LocalDate = testCurrentDate,
    timeZone: TimeZone = TimeZone.UTC,
): String = buildString {
    append("<environment_context>\n")
    append("  <cwd>$cwd</cwd>\n")
    append("  <shell>$shell</shell>\n")
    append("  <current_date>$currentDate</current_date>\n")
    append("  <timezone>$timeZone</timezone>\n")
    append("</environment_context>")
}
