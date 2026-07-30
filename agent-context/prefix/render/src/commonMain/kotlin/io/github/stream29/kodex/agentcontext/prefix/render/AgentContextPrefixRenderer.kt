package io.github.stream29.kodex.agentcontext.prefix.render

import io.github.stream29.kodex.agentcontext.prefix.contract.AgentContextPrefix
import io.github.stream29.kodex.agentcontext.prefix.agentsmd.contract.AgentsMdInstructions
import io.github.stream29.kodex.agentcontext.promptdsl.PromptXmlBuilder
import io.github.stream29.kodex.agentcontext.promptdsl.promptXml
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellType
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.files.Path
import kotlin.time.Clock

/**
 * Renders current host context into the transient prefix of one Responses request.
 *
 * The returned items are intentionally never written to agent storage.
 */
public fun AgentContextPrefix.render(): List<ResponseItem.HistoryItem> {
    val developerSections = listOfNotNull(
        availableSkills.takeIf { it.isNotEmpty() }?.render(),
    )
    val contextualUserSections = buildList {
        if (agentMd.isNotEmpty()) {
            add(agentMd.render(cwd))
        }
        add(renderEnvironmentContext())
    }

    return buildList {
        if (developerSections.isNotEmpty()) {
            add(
                ResponseItem.Message(
                    role = MessageRole.Developer,
                    content = developerSections.map(ContentItem::InputText),
                ),
            )
        }
        if (contextualUserSections.isNotEmpty()) {
            add(
                ResponseItem.Message(
                    role = MessageRole.User,
                    content = contextualUserSections.map(ContentItem::InputText),
                ),
            )
        }
    }
}

private const val AgentsMdOpeningMarker: String = "# AGENTS.md instructions"
private const val ProjectDocSeparator: String = "\n\n--- project-doc ---\n\n"

private fun AgentsMdInstructions.render(cwd: Path): String {
    val heading = buildString {
        append(AgentsMdOpeningMarker)
        if (projectInstructions.isNotEmpty()) {
            append(" for ")
            append(cwd)
        }
    }
    val instructions = buildString {
        userInstruction?.let { instruction -> append(instruction.text) }
        if (projectInstructions.isNotEmpty()) {
            if (userInstruction != null) append(ProjectDocSeparator)
            append(projectInstructions.joinToString(separator = "\n\n") { instruction -> instruction.text })
        }
    }

    return promptXml(indented = false) {
        rawText(heading)
        rawText("\n\n")
        tag("INSTRUCTIONS") {
            rawText("\n")
            rawText(instructions)
            rawText("\n")
        }
    }
}

private fun AgentsMdInstructions.isNotEmpty(): Boolean =
    userInstruction != null || projectInstructions.isNotEmpty()

private fun AgentContextPrefix.renderEnvironmentContext(): String = promptXml {
    tag("environment_context") {
        render(cwd)
        render(shell)
        render(TimeZone.currentSystemDefault())
    }
}

private fun PromptXmlBuilder.render(cwd: Path) {
    tag("cwd") { text(cwd.toString()) }
}

private fun PromptXmlBuilder.render(shell: Shell) {
    val name = when (shell.type) {
        ShellType.Sh -> "sh"
        ShellType.Bash -> "bash"
        ShellType.Zsh -> "zsh"
        ShellType.PowerShell -> "powershell"
        ShellType.Cmd -> "cmd"
    }
    tag("shell") { text(name) }
}

private fun PromptXmlBuilder.render(timeZone: TimeZone) {
    tag("current_date") {
        text(Clock.System.now().toLocalDateTime(timeZone).date.toString())
    }
    tag("timezone") { text(timeZone.toString()) }
}
