package io.github.stream29.kodex.tool.unifiedexec

import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.PropertyBuilder

internal const val WriteStdinYieldTimeDescription: String =
    "Required for both non-empty writes and empty polls. Maximum time to wait for output " +
        "before returning while the process is still running. Calls return immediately when " +
        "the process exits sooner. Choose a value for each call from the expected remaining " +
        "duration. Effective range is 250-600000 ms for non-empty writes and 5000-600000 ms " +
        "for empty polls."

public actual val ExecCommandParametersSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("cmd") {
            required = true
            string { description = "Shell command to execute." }
        }
        property("workdir") {
            string { description = "Working directory for the command. Defaults to the turn cwd." }
        }
        property("tty") {
            boolean { description = "True allocates a PTY for the command; false or omitted uses plain pipes." }
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

public actual val WriteStdinParametersSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("session_id") {
            required = true
            integer { description = "Identifier of the running unified exec session." }
        }
        property("chars") {
            string { description = "Bytes to write to stdin. Defaults to empty, which polls without writing." }
        }
        property("yield_time_ms") {
            required = true
            integer { description = WriteStdinYieldTimeDescription }
        }
        property("max_output_tokens") {
            integer { description = "Output token budget. Defaults to 10000 tokens; larger requests may be capped by policy." }
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
            integer { description = "Session identifier to pass to write_stdin when the process is still running." }
        }
        property("original_token_count") {
            integer { description = "Approximate token count before output truncation." }
        }
        property("output") {
            required = true
            string { description = "Command output text, possibly truncated." }
        }
    }
