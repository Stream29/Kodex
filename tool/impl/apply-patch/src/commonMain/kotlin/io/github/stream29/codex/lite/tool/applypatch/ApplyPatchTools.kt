package io.github.stream29.codex.lite.tool.applypatch

import io.github.stream29.codex.lite.openai.FreeformTool
import io.github.stream29.codex.lite.openai.FreeformToolFormat
import io.github.stream29.codex.lite.openai.FunctionCallOutputBody
import io.github.stream29.codex.lite.openai.FunctionCallOutputPayload
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ToolSpec
import io.github.stream29.codex.lite.tool.contract.Tool

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

    override suspend fun handle(call: ResponseItem.ToolCall): ResponseItem.ToolCallOutput =
        when (call) {
            is ResponseItem.FunctionCall -> ResponseItem.FunctionCallOutput(
                callId = call.callId,
                output = output("apply_patch received function-call JSON payload", success = false),
            )

            is ResponseItem.CustomToolCall -> ResponseItem.CustomToolCallOutput(
                callId = call.callId,
                output = try {
                    client.apply(call.input)
                    output("Success. Patch applied.")
                } catch (error: IllegalArgumentException) {
                    output(error.message ?: "apply_patch failed", success = false)
                },
            )
        }

    private fun output(text: String, success: Boolean = true): FunctionCallOutputPayload =
        FunctionCallOutputPayload(
            body = FunctionCallOutputBody.Text(text),
            success = success,
        )
}

public const val ApplyPatchDescription: String =
    "Use the `apply_patch` tool to edit files. The input must be one apply_patch patch."

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
