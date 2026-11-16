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
            string { description = "Optional human-readable label for the command session." }
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
