package io.github.stream29.kodex.tool.applypatch

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolExecutionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingFunctionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.openai.FreeformTool
import io.github.stream29.kodex.openai.FreeformToolFormat
import io.github.stream29.kodex.openai.ToolSpec
import io.github.stream29.kodex.tool.contract.Tool

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
