package io.github.stream29.codex.lite.agentcontext.prefix.render

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentContextPrefixProvider
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentEnvironment
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentsMdInstruction
import io.github.stream29.codex.lite.agentcontext.prefix.contract.EnvironmentContext
import io.github.stream29.codex.lite.agentcontext.skill.contract.AvailableSkill
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.ResponseItem
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.io.files.Path
import kotlin.test.assertEquals

val agentContextPrefixRendererTest by testSuite {
    test("renders every injection source") {
        val skills = listOf(
            AvailableSkill(
                name = "test-skill",
                description = "test description",
                path = Path("/skills/test-skill/SKILL.md"),
            ),
        )
        val context = EnvironmentContext(
            environments = listOf(
                AgentEnvironment(
                    id = "local",
                    cwd = Path("/workspace"),
                    shell = "bash",
                ),
            ),
            currentDate = LocalDate(2026, 7, 15),
            timeZone = TimeZone.UTC,
        )

        val rendered = contextPrefixProvider(
            availableSkills = skills,
            agentMd = listOf(
                AgentsMdInstruction.Internal(text = "agent instructions"),
            ),
            environmentContext = context,
        ).render()

        assertEquals(
            listOf(
                message(
                    MessageRole.Developer,
                    availableSkills(skills),
                ),
                message(
                    MessageRole.User,
                    agentMd("agent instructions"),
                    environmentContext(
                        cwd = Path("/workspace"),
                        shell = "bash",
                        currentDate = LocalDate(2026, 7, 15),
                        timeZone = TimeZone.UTC,
                    ),
                ),
            ),
            rendered,
        )
    }

    test("renders raw project AGENTS.md sources per environment") {
        val rendered = contextPrefixProvider(
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
        ).render()

        assertEquals(
            listOf(
                message(
                    MessageRole.User,
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
                    environmentContext(
                        cwd = Path("/workspace"),
                        shell = "bash",
                        currentDate = LocalDate(2026, 7, 15),
                        timeZone = TimeZone.UTC,
                    ),
                ),
            ),
            rendered,
        )
    }

    test("renders an environment when optional sources are empty") {
        assertEquals(
            listOf(
                message(
                    MessageRole.User,
                    environmentContext(
                        cwd = Path("/workspace"),
                        shell = "bash",
                        currentDate = LocalDate(2026, 7, 15),
                        timeZone = TimeZone.UTC,
                    ),
                ),
            ),
            contextPrefixProvider().render(),
        )
    }

    test("reads current values from the provider for every render") {
        var revision = 0
        fun skill(revision: Int): AvailableSkill =
            AvailableSkill(
                name = "skill-$revision",
                description = "description $revision",
                path = Path("/skills/$revision/SKILL.md"),
            )

        val provider = object : AgentContextPrefixProvider {
            override val environmentContext: EnvironmentContext
                get() = testEnvironmentContext()

            override val availableSkills: List<AvailableSkill>
                get() = listOf(skill(++revision))

            override val agentMd: List<AgentsMdInstruction>
                get() = emptyList()
        }

        assertEquals(
            listOf(
                message(MessageRole.Developer, availableSkills(listOf(skill(1)))),
                message(
                    MessageRole.User,
                    environmentContext(
                        cwd = Path("/workspace"),
                        shell = "bash",
                        currentDate = LocalDate(2026, 7, 15),
                        timeZone = TimeZone.UTC,
                    ),
                ),
            ),
            provider.render(),
        )
        assertEquals(
            listOf(
                message(MessageRole.Developer, availableSkills(listOf(skill(2)))),
                message(
                    MessageRole.User,
                    environmentContext(
                        cwd = Path("/workspace"),
                        shell = "bash",
                        currentDate = LocalDate(2026, 7, 15),
                        timeZone = TimeZone.UTC,
                    ),
                ),
            ),
            provider.render(),
        )
    }
}

private fun contextPrefixProvider(
    environmentContext: EnvironmentContext = testEnvironmentContext(),
    availableSkills: List<AvailableSkill> = emptyList(),
    agentMd: List<AgentsMdInstruction> = emptyList(),
): AgentContextPrefixProvider {
    val fixedEnvironmentContext = environmentContext
    val fixedAvailableSkills = availableSkills
    val fixedAgentMd = agentMd

    return object : AgentContextPrefixProvider {
        override val environmentContext: EnvironmentContext = fixedEnvironmentContext

        override val availableSkills: List<AvailableSkill> = fixedAvailableSkills

        override val agentMd: List<AgentsMdInstruction> = fixedAgentMd
    }
}

private fun testEnvironmentContext(): EnvironmentContext =
    EnvironmentContext(
        environments = listOf(
            AgentEnvironment(
                id = "local",
                cwd = Path("/workspace"),
                shell = "bash",
            ),
        ),
        currentDate = LocalDate(2026, 7, 15),
        timeZone = TimeZone.UTC,
    )

private fun message(role: MessageRole, vararg sections: String): ResponseItem.Message =
    ResponseItem.Message(
        role = role,
        content = sections.map(ContentItem::InputText),
    )

private fun agentMd(contents: String): String =
    "# AGENTS.md instructions\n\n<INSTRUCTIONS>\n$contents\n</INSTRUCTIONS>"

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
    cwd: Path,
    shell: String,
    currentDate: LocalDate,
    timeZone: TimeZone,
): String = buildString {
    append("<environment_context>\n")
    append("  <cwd>$cwd</cwd>\n")
    append("  <shell>$shell</shell>\n")
    append("  <current_date>$currentDate</current_date>\n")
    append("  <timezone>$timeZone</timezone>\n")
    append("</environment_context>")
}
