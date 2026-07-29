package io.github.stream29.codex.lite.tool.applypatch

import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StablePatchToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StablePatchToolExecutionResult
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.codex.lite.openai.FreeformTool
import io.github.stream29.codex.lite.openai.FreeformToolFormat
import io.github.stream29.codex.lite.openai.FunctionCallOutputBody
import io.github.stream29.codex.lite.openai.FunctionCallOutputPayload
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ToolSpec
import io.github.stream29.codex.lite.tool.contract.Tool
import io.github.stream29.codex.lite.tool.contract.ToolCallResult
import io.github.stream29.codex.lite.utils.applypatch.parsePatch
import kotlinx.serialization.json.JsonPrimitive

public object ApplyPatchTools {
    public const val Name: String = "apply_patch"

    public val spec: ToolSpec = FreeformTool(
        name = Name,
        description = ApplyPatchDescription,
        format = FreeformToolFormat(
            type = "grammar",
            syntax = "lark",
            definition = ApplyPatchGrammar,
        ),
    )

    public fun createTool(client: ApplyPatchToolClient = ApplyPatchToolClient()): Tool =
        ApplyPatchTool(client)
}

public class ApplyPatchTool(
    private val client: ApplyPatchToolClient = ApplyPatchToolClient(),
) : Tool {
    override val spec: ToolSpec = ApplyPatchTools.spec

    override fun close(): Unit = Unit

    override suspend fun handle(call: ResponseItem.ToolCall): ToolCallResult =
        when (call) {
            is ResponseItem.FunctionCall -> {
                val message = "apply_patch received function-call JSON payload"
                ResponseItem.FunctionCallOutput(
                    callId = call.callId,
                    output = output(message, success = false),
                ) to StableTextToolEvent(
                    name = call.name,
                    namespace = call.namespace,
                    arguments = JsonPrimitive(call.arguments),
                    result = message,
                    success = false,
                )
            }

            is ResponseItem.CustomToolCall -> handleCustomCall(call)

            is ResponseItem.ClientToolSearchCall ->
                error("Client tool-search calls are handled by CodexToolRuntime.")
        }

    private suspend fun handleCustomCall(
        call: ResponseItem.CustomToolCall,
    ): ToolCallResult {
        val diff = try {
            call.input.parsePatch()
        } catch (error: IllegalArgumentException) {
            val message = error.message ?: "apply_patch failed"
            return ResponseItem.CustomToolCallOutput(
                callId = call.callId,
                output = output(message, success = false),
            ) to StableTextToolEvent(
                name = call.name,
                namespace = call.namespace,
                arguments = JsonPrimitive(call.input),
                result = message,
                success = false,
            )
        }
        return try {
            val result = client.apply(diff)
            ResponseItem.CustomToolCallOutput(
                callId = call.callId,
                output = output("Success. Patch applied."),
            ) to StablePatchToolEvent(
                diff = diff,
                result = StablePatchToolExecutionResult.Success(result),
            )
        } catch (error: IllegalArgumentException) {
            val message = error.message ?: "apply_patch failed"
            ResponseItem.CustomToolCallOutput(
                callId = call.callId,
                output = output(message, success = false),
            ) to StablePatchToolEvent(
                diff = diff,
                result = StablePatchToolExecutionResult.Failure(message),
            )
        }
    }

    private fun output(text: String, success: Boolean = true): FunctionCallOutputPayload =
        FunctionCallOutputPayload(
            body = FunctionCallOutputBody.Text(text),
            success = success,
        )
}

public const val ApplyPatchDescription: String =
    "Use the `apply_patch` tool to edit files. This is a FREEFORM tool, so do not wrap the patch in JSON."

public val ApplyPatchGrammar: String =
    """
    start: begin_patch environment_id? hunk+ end_patch
    begin_patch: "*** Begin Patch" LF
    environment_id: "*** Environment ID: " filename LF
    end_patch: "*** End Patch" LF?

    hunk: add_hunk | delete_hunk | update_hunk
    add_hunk: "*** Add File: " filename LF add_line+
    delete_hunk: "*** Delete File: " filename LF
    update_hunk: "*** Update File: " filename LF change_move? change?
    filename: /(.+)/
    add_line: "+" /(.*)/ LF -> line

    change_move: "*** Move to: " filename LF
    change: (change_context | change_line)+ eof_line?
    change_context: ("@@" | "@@ " /(.+)/) LF
    change_line: ("+" | "-" | " ") /(.*)/ LF
    eof_line: "*** End of File" LF

    %import common.LF
    """.trimIndent()
