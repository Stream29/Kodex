package io.github.stream29.codex.lite.agentstate.impl

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentcontext.prefix.render.render as renderCollaborationMode
import io.github.stream29.codex.lite.agentcontext.prefix.render.renderMultiAgentMode
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentContextPrefix
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentContextPrefixProvider
import io.github.stream29.codex.lite.agentcontext.prefix.agentsmd.contract.AgentsMdInstruction
import io.github.stream29.codex.lite.agentcontext.prefix.agentsmd.contract.AgentsMdInstructions
import io.github.stream29.codex.lite.agentcontext.prefix.skill.contract.AvailableSkill
import io.github.stream29.codex.lite.agentcontext.prefix.skill.contract.SkillScope
import io.github.stream29.codex.lite.agentcontext.prefix.skill.contract.SkillSource
import io.github.stream29.codex.lite.agentstorage.contract.latestIndex
import io.github.stream29.codex.lite.agentstorage.inmemory.InMemoryCodexAgentStorage
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.ModeKind
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.PlanItemArg
import io.github.stream29.codex.lite.openai.CompactionPhase
import io.github.stream29.codex.lite.openai.CompactionReason
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Response
import io.github.stream29.codex.lite.openai.CompactionTrigger
import io.github.stream29.codex.lite.openai.Response
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.StepStatus
import io.github.stream29.codex.lite.openai.ThreadGoal
import io.github.stream29.codex.lite.openai.ThreadGoalStatus
import io.github.stream29.codex.lite.openai.UpdatePlanArgs
import io.github.stream29.codex.lite.openai.client.test.mockOpenAiClient
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchSourceInfo
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchTools
import io.github.stream29.codex.lite.utils.shellclient.Shell
import io.github.stream29.codex.lite.utils.shellclient.ShellType
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.time.Clock

val agentContextProjectionTest by testSuite {
    test("projects AGENTS.md into a request without persisting it") {
        val storage = InMemoryCodexAgentStorage(settings())
        val requests = mutableListOf<ResponsesApiRequest>()
        val contextPrefixProvider = contextPrefixProvider(
            agentMd = AgentsMdInstructions(
                userInstruction = AgentsMdInstruction(
                    source = Path("/home/stream/AGENTS.md"),
                    text = "user instructions",
                ),
                projectInstructions = listOf(
                    AgentsMdInstruction(
                        source = Path("/workspace/AGENTS.md"),
                        text = "project instructions",
                    ),
                ),
            ),
        )
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse { request ->
                    requests += request
                    flowOf(ResponsesStreamEvent.Completed(Response(id = "response")))
                }
            },
            storage = storage,
            contextPrefixProvider = contextPrefixProvider,
            toolSearchToolSpec = { ToolSearchTools.createToolSearchSpec() },
        )
        val user = userMessage("hello")

        assertEquals(1, agent.appendUserMessage(user.content))
        agent.requestResponseApi().toList()

        assertEquals(
            listOf(
                collaborationMessage(ModeKind.Default),
                multiAgentMessage(),
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

    test("projects Plan mode without plan or goal settings") {
        val storage = InMemoryCodexAgentStorage(
            settings(
                collaborationMode = ModeKind.Plan,
                plan = UpdatePlanArgs(
                    explanation = "Keep this plan out of the model prompt.",
                    plan = listOf(
                        PlanItemArg(
                            step = "Complete the implementation.",
                            status = StepStatus.InProgress,
                        ),
                    ),
                ),
                goal = ThreadGoal(
                    objective = "Finish the implementation.",
                    status = ThreadGoalStatus.Active,
                ),
            ),
        )
        val requests = mutableListOf<ResponsesApiRequest>()
        val skillCatalog = listOf(
            AvailableSkill(
                name = "test-skill",
                description = "test description",
                path = Path("/skills/test-skill/SKILL.md"),
                source = SkillSource(
                    authorityId = "test",
                    scope = SkillScope.User,
                    root = Path("/skills"),
                ),
            ),
        )
        val contextPrefixProvider = contextPrefixProvider(
            skills = skillCatalog,
            agentMd = userAgentsMd("agent instructions"),
        )
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse { request ->
                    requests += request
                    flowOf(ResponsesStreamEvent.Completed(Response(id = "response")))
                }
            },
            storage = storage,
            contextPrefixProvider = contextPrefixProvider,
            toolSearchToolSpec = { ToolSearchTools.createToolSearchSpec() },
        )
        val user = userMessage("use a tool")

        agent.appendUserMessage(user.content)
        agent.requestResponseApi().toList()

        assertEquals(
            listOf(
                collaborationMessage(ModeKind.Plan),
                multiAgentMessage(),
                developerMessage(
                    availableSkills(skillCatalog),
                ),
                contextualUserMessage(
                    agentMd("agent instructions"),
                    environmentContext(),
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
        val firstToolSearchSpec = ToolSearchTools.createToolSearchSpec(
            searchableSources = listOf(ToolSearchSourceInfo("first")),
        )
        val secondToolSearchSpec = ToolSearchTools.createToolSearchSpec(
            searchableSources = listOf(ToolSearchSourceInfo("second")),
        )
        var currentToolSearchSpec = firstToolSearchSpec
        val contextPrefixProvider = contextPrefixProvider(
            agentMd = userAgentsMd("agent instructions"),
        )
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse { request ->
                    requests += request
                    flowOf(ResponsesStreamEvent.Completed(Response(id = "response_${requests.size}")))
                }
            },
            storage = storage,
            contextPrefixProvider = contextPrefixProvider,
            toolSearchToolSpec = { currentToolSearchSpec },
        )
        val user = userMessage("continue")

        agent.appendUserMessage(user.content)
        agent.requestResponseApi().toList()
        currentToolSearchSpec = secondToolSearchSpec
        agent.requestResponseApi().toList()

        assertEquals(
            listOf(
                listOf(
                    collaborationMessage(ModeKind.Default),
                    multiAgentMessage(),
                    contextualUserMessage(agentMd("agent instructions"), environmentContext()),
                    user,
                ),
                listOf(
                    collaborationMessage(ModeKind.Default),
                    multiAgentMessage(),
                    contextualUserMessage(agentMd("agent instructions"), environmentContext()),
                    user,
                ),
            ),
            requests.map(ResponsesApiRequest::input),
        )
        assertEquals(
            listOf(firstToolSearchSpec, secondToolSearchSpec),
            requests.map { request -> request.tools.last() },
        )
        assertEquals(user, storage.history[1])
        assertEquals(1, storage.history.latestIndex())
    }

    test("resolves the bound context prefix for each response request") {
        val initialSettings = settings()
        val updatedSettings = initialSettings.copy(cwd = Path("/updated-workspace"))
        val storage = InMemoryCodexAgentStorage(initialSettings)
        val requests = mutableListOf<ResponsesApiRequest>()
        val resolvedSettings = mutableListOf<CodexAgentSettings>()
        var currentPrefix = contextPrefix(
            agentMd = userAgentsMd("agent instructions 1"),
        )
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse { request ->
                    requests += request
                    flowOf(ResponsesStreamEvent.Completed(Response(id = "response_${requests.size}")))
                }
            },
            storage = storage,
            contextPrefixProvider = { settings ->
                resolvedSettings += settings
                currentPrefix
            },
            toolSearchToolSpec = { ToolSearchTools.createToolSearchSpec() },
        )
        val user = userMessage("continue")

        agent.appendUserMessage(user.content)
        agent.requestResponseApi().toList()
        agent.updateSettings(updatedSettings)
        currentPrefix = contextPrefix(
            agentMd = userAgentsMd("agent instructions 2"),
        )
        agent.requestResponseApi().toList()

        assertEquals(
            listOf(
                listOf(
                    collaborationMessage(ModeKind.Default),
                    multiAgentMessage(),
                    contextualUserMessage(agentMd("agent instructions 1"), environmentContext()),
                    user,
                ),
                listOf(
                    collaborationMessage(ModeKind.Default),
                    multiAgentMessage(),
                    contextualUserMessage(agentMd("agent instructions 2"), environmentContext()),
                    user,
                ),
            ),
            requests.map(ResponsesApiRequest::input),
        )
        assertEquals(listOf(initialSettings, updatedSettings), resolvedSettings)
        assertEquals(user, storage.history[1])
        assertEquals(1, storage.history.latestIndex())
    }

    test("remote compaction does not persist context prefix") {
        val storage = InMemoryCodexAgentStorage(settings())
        val compactionRequests = mutableListOf<ResponsesApiRequest>()
        val toolSearchSpec = ToolSearchTools.createToolSearchSpec(
            searchableSources = listOf(ToolSearchSourceInfo("compaction")),
        )
        var prefixResolutionCount = 0
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response { request, _, _, _ ->
                    compactionRequests += request
                    RemoteCompactionV2Response(
                        compactionOutput = ResponseItem.Compaction(encryptedContent = "compacted"),
                        completedResponse = null,
                    )
                }
            },
            storage = storage,
            contextPrefixProvider = { _ ->
                prefixResolutionCount += 1
                contextPrefix()
            },
            toolSearchToolSpec = { toolSearchSpec },
        )
        val user = userMessage("compact this")

        agent.appendUserMessage(user.content)
        val compactIndex = agent.compact(
            trigger = CompactionTrigger.Auto,
            reason = CompactionReason.ContextLimit,
            phase = CompactionPhase.PreTurn,
        )

        assertEquals(listOf(user, ResponseItem.CompactionTrigger), compactionRequests.single().input)
        assertEquals(0, prefixResolutionCount)
        assertEquals(toolSearchSpec, compactionRequests.single().tools.last())
        assertEquals(2, compactIndex)
        assertEquals(user, storage.history[1])
        assertEquals(
            ResponseItem.ContextCompaction(encryptedContent = "compacted"),
            storage.history[compactIndex],
        )
    }

    test("renders raw project AGENTS.md sources in discovery order") {
        val storage = InMemoryCodexAgentStorage(settings())
        val requests = mutableListOf<ResponsesApiRequest>()
        val contextPrefixProvider = contextPrefixProvider(
            agentMd = AgentsMdInstructions(
                userInstruction = AgentsMdInstruction(
                    source = Path("/home/stream/AGENTS.md"),
                    text = "user instructions",
                ),
                projectInstructions = listOf(
                    AgentsMdInstruction(
                        source = Path("/workspace/AGENTS.md"),
                        text = "workspace instructions",
                    ),
                    AgentsMdInstruction(
                        source = Path("/workspace/nested/AGENTS.md"),
                        text = "nested workspace instructions",
                    ),
                ),
            ),
        )
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse { request ->
                    requests += request
                    flowOf(ResponsesStreamEvent.Completed(Response(id = "response")))
                }
            },
            storage = storage,
            contextPrefixProvider = contextPrefixProvider,
            toolSearchToolSpec = { ToolSearchTools.createToolSearchSpec() },
        )
        val user = userMessage("hello")

        agent.appendUserMessage(user.content)
        agent.requestResponseApi().toList()

        assertEquals(
            listOf(
                collaborationMessage(ModeKind.Default),
                multiAgentMessage(),
                contextualUserMessage(
                    agentMdForDirectory(
                        """
                        user instructions

                        --- project-doc ---

                        workspace instructions

                        nested workspace instructions
                        """.trimIndent(),
                        directory = "/workspace",
                    ),
                    environmentContext(),
                ),
                user,
            ),
            requests.single().input,
        )
    }
}

private val testShell: Shell = Shell(ShellType.Bash, Path("/bin/bash"))

private fun contextPrefixProvider(
    cwd: Path = Path("/workspace"),
    shell: Shell = testShell,
    skills: List<AvailableSkill> = emptyList(),
    agentMd: AgentsMdInstructions = AgentsMdInstructions(),
): AgentContextPrefixProvider = { _ ->
    contextPrefix(cwd, shell, skills, agentMd)
}

private fun contextPrefix(
    cwd: Path = Path("/workspace"),
    shell: Shell = testShell,
    skills: List<AvailableSkill> = emptyList(),
    agentMd: AgentsMdInstructions = AgentsMdInstructions(),
): AgentContextPrefix = AgentContextPrefix(
    cwd = cwd,
    shell = shell,
    agentMd = agentMd,
    availableSkills = skills,
)

private fun userAgentsMd(text: String): AgentsMdInstructions =
    AgentsMdInstructions(
        userInstruction = AgentsMdInstruction(
            source = Path("/home/stream/AGENTS.md"),
            text = text,
        ),
    )

private fun settings(
    collaborationMode: ModeKind = ModeKind.Default,
    plan: UpdatePlanArgs = UpdatePlanArgs(plan = emptyList()),
    goal: ThreadGoal? = null,
): CodexAgentSettings =
    CodexAgentSettings(
        model = OpenAiModelId("test-model"),
        collaborationMode = collaborationMode,
        plan = plan,
        goal = goal,
    )

private fun userMessage(text: String): ResponseItem.Message =
    message(MessageRole.User, text)

private fun developerMessage(vararg sections: String): ResponseItem.Message =
    message(MessageRole.Developer, *sections)

private fun collaborationMessage(mode: ModeKind): ResponseItem.Message =
    developerMessage(mode.renderCollaborationMode())

private fun multiAgentMessage(): ResponseItem.Message =
    developerMessage(settings().reasoning.effort.renderMultiAgentMode())

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
): String {
    val timeZone = TimeZone.currentSystemDefault()
    val currentDate = Clock.System.now().toLocalDateTime(timeZone).date
    return buildString {
        append("<environment_context>\n")
        append("  <cwd>$cwd</cwd>\n")
        append("  <shell>$shell</shell>\n")
        append("  <current_date>$currentDate</current_date>\n")
        append("  <timezone>$timeZone</timezone>\n")
        append("</environment_context>")
    }
}
