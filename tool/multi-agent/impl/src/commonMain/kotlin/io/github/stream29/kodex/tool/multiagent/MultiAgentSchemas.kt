package io.github.stream29.kodex.tool.multiagent

import kotlinx.schema.json.GenericPropertyDefinition
import kotlinx.schema.json.JsonSchemaConstants.Types.NULL_TYPE
import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.PropertyBuilder
import kotlinx.schema.json.obj
import kotlinx.schema.json.string

public val SpawnAgentParametersSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("task_name") {
            required = true
            string { description = "Task name for the new agent. Use lowercase letters, digits, and underscores." }
        }
        property("message") {
            required = true
            string { description = "Initial plain-text task for the new agent." }
        }
        property("fork_turns") {
            string {
                description = "Optional number of turns to fork. Defaults to `all`. Use `none`, `all`, or a positive integer string such as `3` to fork only the most recent turns."
            }
        }
        property("model") {
            string { description = "Model override for the new agent. Omit unless an explicit override is needed." }
        }
        property("reasoning_effort") {
            string { description = "Reasoning effort override for the new agent. Omit to inherit the parent effort." }
        }
        property("service_tier") {
            string { description = "Service tier override for the new agent. Omit unless explicitly requested." }
        }
    }

public val SpawnAgentOutputSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("task_name") {
            required = true
            string { description = "Full canonical Agent path for the spawned Agent." }
        }
        property("nickname") {
            required = true
            anyOf {
                description = "User-facing nickname for the spawned agent when available."
                string()
                addOption(GenericPropertyDefinition(type = NULL_TYPE))
            }
        }
    }

public val SendMessageParametersSchema: ObjectPropertyDefinition = messageParametersSchema(
    targetDescription = "Canonical Agent path to message (from spawn_agent).",
    messageDescription = "Message text to queue on the target agent.",
)

public val FollowupTaskParametersSchema: ObjectPropertyDefinition = messageParametersSchema(
    targetDescription = "Canonical Agent path to send a follow-up task to (from spawn_agent).",
    messageDescription = "Message text to send to the target agent.",
)

public val WaitAgentParametersSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("timeout_ms") {
            integer {
                description = "Maximum wait duration in milliseconds."
                minimum = MultiAgentTools.MinWaitTimeoutMillis.toDouble()
                maximum = MultiAgentTools.MaxWaitTimeoutMillis.toDouble()
            }
        }
    }

public val WaitAgentOutputSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("message") {
            required = true
            string { description = "Brief wait summary without the agent's final content." }
        }
        property("timed_out") {
            required = true
            boolean { description = "Whether no pending steering message arrived before the timeout." }
        }
    }

public val InterruptAgentParametersSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("target") {
            required = true
            string { description = "Canonical Agent path to interrupt (from spawn_agent)." }
        }
    }

public val InterruptAgentOutputSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("previous_status") {
            required = true
            agentStatus("The agent status observed before the interrupt request was handled.")
        }
    }

public val ListAgentsParametersSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("path_prefix") {
            string {
                description = "Canonical Agent-path prefix without a trailing slash. Omit to list all live agents."
            }
        }
    }

public val ListAgentsOutputSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("agents") {
            required = true
            array {
                description = "Live agents visible in the current root thread tree."
                ofObject {
                    additionalProperties = false
                    property("agent_name") {
                        required = true
                        string { description = "Full canonical Agent path for the Agent." }
                    }
                    property("agent_status") {
                        required = true
                        agentStatus("Last known status of the agent.")
                    }
                }
            }
        }
    }

private fun messageParametersSchema(
    targetDescription: String,
    messageDescription: String,
): ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("target") {
            required = true
            string { description = targetDescription }
        }
        property("message") {
            required = true
            string { description = messageDescription }
        }
    }

private fun PropertyBuilder.agentStatus(description: String) =
    string {
        this.description = description
        enum = listOf("running", "idle")
    }
