package io.github.stream29.kodex.tool.unifiedexec

import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.PropertyBuilder

public actual val ExecCommandParametersSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("cmd") {
            required = true
            string { description = "Shell command to execute in a pseudoterminal." }
        }
        property("name") {
            required = true
            string {
                description = "Short, specific, single-line intent in the conversation's language. " +
                    "Describe what the command is for; do not copy the command, include sensitive literals, or claim success."
            }
        }
        property("workdir") {
            string { description = "Working directory for the command. Defaults to the turn cwd." }
        }
        property("yield_time_ms") {
            required = true
            integer { description = renderExecCommandYieldTimeDescription(execCommandHostPlatform) }
        }
        property("max_output_tokens") {
            integer { description = "Output token budget. Defaults to 10000 tokens; larger requests may be capped by policy." }
        }
        property("shell") {
            string { description = ExecCommandShellDescription }
        }
    }
