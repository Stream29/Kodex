package io.github.stream29.codex.lite.agentcontext.prefix.render

import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentContextPrefixProvider
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentEnvironment
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentsMdInstruction
import io.github.stream29.codex.lite.agentcontext.prefix.contract.EnvironmentContext
import io.github.stream29.codex.lite.agentcontext.promptdsl.PromptXmlBuilder
import io.github.stream29.codex.lite.agentcontext.promptdsl.promptXml
import io.github.stream29.codex.lite.agentcontext.skill.render.render
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.ResponseItem
import kotlin.jvm.JvmName

/**
 * Renders current host context into the transient prefix of one Responses request.
 *
 * The returned items are intentionally never written to agent storage.
 */
public fun AgentContextPrefixProvider.render(): List<ResponseItem> {
    val currentAvailableSkills = availableSkills
    val currentAgentMd = agentMd
    val currentEnvironmentContext = environmentContext
    val developerSections = listOfNotNull(
        currentAvailableSkills.takeIf { it.isNotEmpty() }?.render(),
    )
    val contextualUserSections = listOfNotNull(
        currentAgentMd.takeIf { it.isNotEmpty() }?.render(),
        currentEnvironmentContext.render(),
    )

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

@JvmName("renderAgentsMd")
private fun List<AgentsMdInstruction>.render(): String {
    val entries = this
    val projectEntries = filterIsInstance<AgentsMdInstruction.Project>()
    val hasMultipleProjectEnvironments = projectEntries
        .map { entry -> entry.environmentId to entry.cwd }
        .distinct()
        .size > 1
    val heading = buildString {
        append(AgentsMdOpeningMarker)
        if (!hasMultipleProjectEnvironments) {
            projectEntries.firstOrNull()?.cwd?.let { value ->
                append(" for ")
                append(value)
            }
        }
    }
    val instructions = buildString {
        appendAgentsMdEntries(entries, hasMultipleProjectEnvironments)
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

private fun StringBuilder.appendAgentsMdEntries(
    entries: List<AgentsMdInstruction>,
    hasMultipleProjectEnvironments: Boolean,
): Unit {
    var hasPrevious = false
    var previousWasProject = false
    var previousProjectEnvironment: AgentsMdInstruction.Project? = null

    entries.forEach { entry ->
        if (hasPrevious) {
            append(
                if (!hasMultipleProjectEnvironments && entry is AgentsMdInstruction.Project && !previousWasProject) {
                    ProjectDocSeparator
                } else {
                    "\n\n"
                },
            )
        }
        if (hasMultipleProjectEnvironments && entry is AgentsMdInstruction.Project) {
            val sameEnvironment = previousProjectEnvironment?.let { previous ->
                previous.environmentId == entry.environmentId && previous.cwd == entry.cwd
            } == true
            if (!sameEnvironment) {
                append("for `")
                append(entry.environmentId)
                append("` with root ")
                append(entry.cwd)
                append("\n\n")
            }
            previousProjectEnvironment = entry
        } else {
            previousProjectEnvironment = null
        }
        append(entry.text)
        previousWasProject = entry is AgentsMdInstruction.Project
        hasPrevious = true
    }
}

private fun EnvironmentContext.render(): String = promptXml {
    tag("environment_context") {
        when {
            environments.size == 1 -> appendEnvironment(environments.single())
            environments.isNotEmpty() -> {
                tag("environments") {
                    environments.forEach { environment ->
                        tag("environment", attributes = mapOf("id" to environment.id)) {
                            appendEnvironment(environment)
                        }
                    }
                }
            }
        }
        tag("current_date") { text(currentDate.toString()) }
        tag("timezone") { text(timeZone.toString()) }
    }
}

private fun PromptXmlBuilder.appendEnvironment(environment: AgentEnvironment): Unit {
    tag("cwd") { text(environment.cwd.toString()) }
    tag("shell") { text(environment.shell) }
}
