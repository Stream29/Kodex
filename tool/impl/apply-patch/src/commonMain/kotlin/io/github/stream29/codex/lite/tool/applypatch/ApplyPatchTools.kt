package io.github.stream29.codex.lite.tool.applypatch

import io.github.stream29.codex.lite.openai.FreeformTool
import io.github.stream29.codex.lite.openai.FreeformToolFormat
import io.github.stream29.codex.lite.openai.FunctionCallOutputBody
import io.github.stream29.codex.lite.openai.FunctionCallOutputPayload
import io.github.stream29.codex.lite.openai.ToolSpec
import io.github.stream29.codex.lite.tool.contract.Tool
import io.github.stream29.codex.lite.tool.contract.ToolCallPayload
import io.github.stream29.codex.lite.tool.contract.ToolCallResult

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

    override suspend fun handle(payload: ToolCallPayload): ToolCallResult {
        val patch = when (payload) {
            is ToolCallPayload.CustomToolCall -> payload.call.input
            is ToolCallPayload.FunctionCall -> return output("apply_patch received function-call JSON payload", false)
        }

        return try {
            client.apply(patch)
            output("Success. Patch applied.")
        } catch (error: IllegalArgumentException) {
            output(error.message ?: "apply_patch failed", success = false)
        }
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
