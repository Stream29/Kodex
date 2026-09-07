package io.github.stream29.kodex.tool.unifiedexec

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.openai.ResponsesApiTool
import io.github.stream29.kodex.tool.contract.Tool
import io.github.stream29.kodex.tool.contract.typedTool

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
                delegate = typedTool(
                    spec = execCommandSpec,
                    select = { event ->
                        (event as? PendingCommandExecutionToolEvent)
                            ?.takeIf { it.action is PendingCommandExecutionAction.ExecCommand }
                    },
                ) { pending ->
                    val action = pending.action as PendingCommandExecutionAction.ExecCommand
                    try {
                        StableCommandExecutionToolEvent(
                            callId = pending.callId,
                            itemId = pending.itemId,
                            action = StableCommandExecutionAction.ExecCommand(action.arguments),
                            result = StableCommandExecutionResult.Output(
                                client.execCommand(action.arguments),
                            ),
                        )
                    } catch (failure: UnifiedExecToolException) {
                        StableCommandExecutionToolEvent(
                            callId = pending.callId,
                            itemId = pending.itemId,
                            action = StableCommandExecutionAction.ExecCommand(action.arguments),
                            result = StableCommandExecutionResult.Failure(
                                failure.message ?: "exec_command failed.",
                            ),
                        )
                    }
                },
                client = client,
            ),
            CloseableUnifiedExecTool(
                delegate = typedTool(
                    spec = writeStdinSpec,
                    select = { event ->
                        (event as? PendingCommandExecutionToolEvent)
                            ?.takeIf { it.action is PendingCommandExecutionAction.WriteStdin }
                    },
                ) { pending ->
                    val action = pending.action as PendingCommandExecutionAction.WriteStdin
                    try {
                        StableCommandExecutionToolEvent(
                            callId = pending.callId,
                            itemId = pending.itemId,
                            action = StableCommandExecutionAction.WriteStdin(action.arguments),
                            result = StableCommandExecutionResult.Output(
                                client.writeStdin(action.arguments),
                            ),
                        )
                    } catch (failure: UnifiedExecToolException) {
                        StableCommandExecutionToolEvent(
                            callId = pending.callId,
                            itemId = pending.itemId,
                            action = StableCommandExecutionAction.WriteStdin(action.arguments),
                            result = StableCommandExecutionResult.Failure(
                                failure.message ?: "write_stdin failed.",
                            ),
                        )
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

    override suspend fun handle(pending: PendingToolEvent): StableCleanEvent.CompletedTool =
        delegate.handle(pending)

    override fun close() {
        client.close()
    }
}
