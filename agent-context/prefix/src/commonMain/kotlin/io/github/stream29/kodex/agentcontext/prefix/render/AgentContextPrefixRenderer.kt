package io.github.stream29.kodex.agentcontext.prefix.render

import io.github.stream29.kodex.agentcontext.prefix.agentsmd.contract.AgentsMdInstructions
import io.github.stream29.kodex.agentcontext.prefix.contract.AgentContextPrefix
import io.github.stream29.kodex.agentcontext.promptdsl.PromptXmlBuilder
import io.github.stream29.kodex.agentcontext.promptdsl.promptXml
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellType
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.files.Path

public actual fun AgentContextPrefix.render(): List<ResponseItem.HistoryItem> {
    val developerSections = listOfNotNull(
        renderToolCliInstructions(requestUserInputMode),
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

internal fun renderToolCliInstructions(mode: RequestUserInputMode): String = buildString {
    appendLine("<tool_cli_instructions>")
    appendLine("## Kodex Tool Surface & CLI")
    appendLine("Direct model tools are fixed to: `exec_command`, `apply_patch`, `read_media`, `update_plan`, and `await_event`.")
    appendLine("Every `exec_command` subprocess automatically inherits `KODEX_AGENT_ID` and `KODEX_HOME`. Use the `kodex` CLI inside `exec_command` for extended capabilities:")
    appendLine("- `kodex tool list`: List all built-in CLI tools and enabled MCP server tools (`<command>\\t<summary>`). Filter with `grep` and run `<command> --help` to inspect options or MCP JSON schemas.")
    appendLine("- `kodex terminal (list | read | write | kill)`: Inspect output, write stdin (`--stdin` / `--eof`), or terminate background shell sessions. Call `await_event(terminals = [<id>])` instead of polling with `sleep`.")
    when (mode) {
        RequestUserInputMode.AskUser -> {
            appendLine("- `kodex ask (create | list | get)`: Create a non-blocking structured question for the user (`--json` or `--stdin`), then call `await_event` when you need the user's response before continuing.")
        }
        RequestUserInputMode.NoQuestion -> {
            appendLine("- User questions are disabled (`NoQuestion` mode): do NOT ask the user questions or invoke external question prompts; proceed autonomously using the best evidence available.")
        }
    }
    appendLine("- `kodex schedule (create | list | delete)`: Manage persisted one-shot (`--delay-seconds` / `--at`) or recurring (`--cron`) schedules.")
    appendLine("- `kodex session status`: Read current ISO-8601 UTC time, session configuration, and context-window token budget.")
    appendLine("- `kodex search`: Run web search (`-q <query>`) or full `SearchCommands` payloads (`--json` / `--stdin`).")
    appendLine("- `kodex image`: Generate or edit PNG images (`-p <prompt>`, repeatable `-i <path>`). Use `read_media(path)` to inspect local or generated images.")
    appendLine("- `kodex mcp (list | status | reconnect | enable | disable)` and `kodex <server> <tool> [--json <JSON> | --stdin]`: Manage MCP servers and invoke tools on enabled MCP servers.")
    append("</tool_cli_instructions>")
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
        append(globalInstructions.joinToString(separator = "\n\n") { instruction -> instruction.text })
        if (projectInstructions.isNotEmpty()) {
            if (globalInstructions.isNotEmpty()) append(ProjectDocSeparator)
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
    globalInstructions.isNotEmpty() || projectInstructions.isNotEmpty()

private fun AgentContextPrefix.renderEnvironmentContext(): String = promptXml {
    tag("environment_context") {
        render(cwd)
        render(shell)
        render(TimeZone.currentSystemDefault())
        render(sessionMeta)
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

private fun PromptXmlBuilder.render(sessionMeta: io.github.stream29.kodex.agentcontext.prefix.contract.AgentSessionMeta) {
    tag("current_session") {
        tag("storage_uri") { text(sessionMeta.uri) }
        tag("name") { text(sessionMeta.name) }
    }
}
