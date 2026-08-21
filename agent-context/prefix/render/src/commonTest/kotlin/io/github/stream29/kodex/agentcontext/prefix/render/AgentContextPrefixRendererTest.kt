package io.github.stream29.kodex.agentcontext.prefix.render

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentcontext.prefix.agentsmd.contract.AgentsMdInstruction
import io.github.stream29.kodex.agentcontext.prefix.agentsmd.contract.AgentsMdInstructions
import io.github.stream29.kodex.agentcontext.prefix.contract.AgentContextPrefix
import io.github.stream29.kodex.agentcontext.prefix.skill.contract.AvailableSkill
import io.github.stream29.kodex.agentcontext.prefix.skill.contract.SkillScope
import io.github.stream29.kodex.agentcontext.prefix.skill.contract.SkillSource
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellType
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.time.Clock

val agentContextPrefixRendererTest by testSuite {
    test("renders every injection source") {
        val skills = listOf(testSkill(1))
        val rendered = contextPrefix(
            agentMd = AgentsMdInstructions(
                globalInstructions = listOf(
                    AgentsMdInstruction(
                        source = Path("/home/stream/.agents/AGENTS.md"),
                        text = "agent instructions",
                    ),
                ),
            ),
            availableSkills = skills,
        ).render()

        assertEquals(
            listOf(
                message(MessageRole.Developer, availableSkills(skills)),
                message(
                    MessageRole.User,
                    agentMd("agent instructions"),
                    environmentContext(),
                ),
            ),
            rendered,
        )
    }

    test("renders global and project AGENTS.md sources in discovery order") {
        val rendered = contextPrefix(
            agentMd = AgentsMdInstructions(
                globalInstructions = listOf(
                    AgentsMdInstruction(
                        Path("/home/stream/.agents/AGENTS.md"),
                        "Agents instructions",
                    ),
                    AgentsMdInstruction(
                        Path("/home/stream/.kodex/AGENTS.md"),
                        "Kodex instructions",
                    ),
                ),
                projectInstructions = listOf(
                    AgentsMdInstruction(
                        Path("/workspace/AGENTS.md"),
                        "workspace instructions",
                    ),
                    AgentsMdInstruction(
                        Path("/workspace/nested/AGENTS.md"),
                        "nested workspace instructions",
                    ),
                ),
            ),
        ).render()

        assertEquals(
            listOf(
                message(
                    MessageRole.User,
                    agentMdForDirectory(
                        """
                        Agents instructions

                        Kodex instructions

                        --- project-doc ---

                        workspace instructions

                        nested workspace instructions
                        """.trimIndent(),
                        directory = "/workspace",
                    ),
                    environmentContext(),
                ),
            ),
            rendered,
        )
    }

    test("renders the explicitly supplied catalog without shared provider state") {
        assertEquals(
            listOf(
                message(MessageRole.Developer, availableSkills(listOf(testSkill(2)))),
                message(MessageRole.User, environmentContext()),
            ),
            contextPrefix(availableSkills = listOf(testSkill(2))).render(),
        )
    }
}

private val testSkillSource = SkillSource(
    authorityId = "test",
    scope = SkillScope.User,
    root = Path("/skills"),
)

private fun testSkill(revision: Int): AvailableSkill = AvailableSkill(
    name = "skill-$revision",
    description = "description $revision",
    path = Path("/skills/$revision/SKILL.md"),
    source = testSkillSource,
)

private fun contextPrefix(
    agentMd: AgentsMdInstructions = AgentsMdInstructions(),
    availableSkills: List<AvailableSkill> = emptyList(),
): AgentContextPrefix = AgentContextPrefix(
    cwd = Path("/workspace"),
    shell = Shell(ShellType.Bash, Path("/bin/bash")),
    agentMd = agentMd,
    availableSkills = availableSkills,
)

private fun message(role: MessageRole, vararg sections: String): ResponseItem.Message =
    ResponseItem.Message(role = role, content = sections.map(ContentItem::InputText))

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
        append("- name: ${skill.name}\n")
        append("  description: ${skill.description}\n")
        append("  path: ${skill.path}\n")
    }
    append("</skills_instructions>")
}

private fun environmentContext(): String {
    val timeZone = TimeZone.currentSystemDefault()
    val currentDate = Clock.System.now().toLocalDateTime(timeZone).date
    return """
    <environment_context>
      <cwd>/workspace</cwd>
      <shell>bash</shell>
      <current_date>$currentDate</current_date>
      <timezone>$timeZone</timezone>
    </environment_context>
    """.trimIndent()
}
