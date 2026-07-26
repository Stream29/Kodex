package io.github.stream29.codex.lite.tool.unifiedexec

import io.github.stream29.codex.lite.openai.ResponsesApiTool
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.tool.builder.jsonTool
import io.github.stream29.codex.lite.tool.builder.jsonToolFailure
import io.github.stream29.codex.lite.tool.builder.jsonToolSuccess
import io.github.stream29.codex.lite.tool.contract.Tool

public object UnifiedExecTools {
    public const val ExecCommandName: String = "exec_command"
    public const val WriteStdinName: String = "write_stdin"

    public const val ExecCommandDescription: String =
        "Runs a command in a PTY, returning output or a session ID for ongoing interaction."
    public const val WriteStdinDescription: String =
        "Writes characters to an existing unified exec session and returns recent output."

    public val execCommandSpec: ResponsesApiTool =
        ResponsesApiTool(
            name = ExecCommandName,
            description = renderExecCommandDescription(execCommandHostPlatform),
            strict = false,
            parameters = ExecCommandParametersSchema,
            outputSchema = UnifiedExecOutputSchema,
        )

    public val writeStdinSpec: ResponsesApiTool =
        ResponsesApiTool(
            name = WriteStdinName,
            description = WriteStdinDescription,
            strict = false,
            parameters = WriteStdinParametersSchema,
            outputSchema = UnifiedExecOutputSchema,
        )

    /** Creates both tools around one shared [UnifiedExecToolClient]. */
    public fun createTools(client: UnifiedExecToolClient): List<Tool> =
        listOf(
            CloseableUnifiedExecTool(
                delegate = jsonTool(
                    spec = execCommandSpec,
                    inputDeserializer = ExecCommandArguments.serializer(),
                    outputSerializer = UnifiedExecOutput.serializer(),
                ) { arguments ->
                    try {
                        jsonToolSuccess(client.execCommand(arguments))
                    } catch (failure: UnifiedExecToolException) {
                        jsonToolFailure(failure.message ?: "exec_command failed.")
                    }
                },
                client = client,
            ),
            CloseableUnifiedExecTool(
                delegate = jsonTool(
                    spec = writeStdinSpec,
                    inputDeserializer = WriteStdinArguments.serializer(),
                    outputSerializer = UnifiedExecOutput.serializer(),
                ) { arguments ->
                    try {
                        jsonToolSuccess(client.writeStdin(arguments))
                    } catch (failure: UnifiedExecToolException) {
                        jsonToolFailure(failure.message ?: "write_stdin failed.")
                    }
                },
                client = client,
            ),
        )
}

private class CloseableUnifiedExecTool(
    private val delegate: Tool,
    private val client: UnifiedExecToolClient,
) : Tool {
    override val spec: ResponsesApiTool
        get() = delegate.spec as ResponsesApiTool

    override suspend fun handle(call: ResponseItem.ToolCall): ResponseItem.ToolCallOutput =
        delegate.handle(call)

    override fun close() {
        client.close()
    }
}
