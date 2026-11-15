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

public actual val UnifiedExecOutputSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("chunk_id") {
            string { description = "Chunk identifier included when the response reports one." }
        }
        property("wall_time_seconds") {
            required = true
            number { description = "Elapsed wall time spent waiting for output in seconds." }
        }
        property("exit_code") {
            integer { description = "Process exit code when the command finished during this call." }
        }
        property("session_id") {
            integer { description = "Session identifier of the background terminal session when the process is still running." }
        }
        property("original_token_count") {
            integer { description = "Approximate token count before output truncation." }
        }
        property("output") {
            required = true
            string { description = "Command output text, possibly truncated." }
        }
    }
