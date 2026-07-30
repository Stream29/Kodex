package io.github.stream29.codex.lite.tool.applypatch

import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StablePatchToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StablePatchToolExecutionResult
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingFunctionToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingPatchToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.codex.lite.openai.FreeformTool
import io.github.stream29.codex.lite.openai.FreeformToolFormat
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

    override suspend fun handle(pending: PendingToolEvent): StableCleanEvent.CompletedTool =
        when (pending) {
            is PendingFunctionToolEvent -> {
                val message = "apply_patch received function-call JSON payload"
                StableTextToolEvent(
                    callId = pending.callId,
                    itemId = pending.itemId,
                    name = pending.name,
                    namespace = pending.namespace,
                    arguments = pending.arguments,
                    result = message,
                    success = false,
                )
            }

            is PendingPatchToolEvent -> handlePatch(pending)

            else -> error("apply_patch requires a parsed pending patch event.")
        }

    private suspend fun handlePatch(
        pending: PendingPatchToolEvent,
    ): StableCleanEvent.CompletedTool {
        return try {
            val result = client.apply(pending.diff)
            StablePatchToolEvent(
                callId = pending.callId,
                itemId = pending.itemId,
                diff = pending.diff,
                result = StablePatchToolExecutionResult.Success(result),
            )
        } catch (error: IllegalArgumentException) {
            val message = error.message ?: "apply_patch failed"
            StablePatchToolEvent(
                callId = pending.callId,
                itemId = pending.itemId,
                diff = pending.diff,
                result = StablePatchToolExecutionResult.Failure(message),
            )
        }
    }

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
