@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.tool.applypatch

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatch
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolExecutionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextTool
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingFunctionToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPatch
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolCall
import io.github.stream29.kodex.openai.FreeformTool
import io.github.stream29.kodex.openai.FreeformToolFormat
import io.github.stream29.kodex.openai.ToolSpec
import io.github.stream29.kodex.tool.contract.Tool
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

internal actual object ApplyPatchToolImplementation {
    public actual val spec: ToolSpec = FreeformTool(
        name = ApplyPatchTools.Name,
        description = ApplyPatchDescription,
        format = FreeformToolFormat(
            type = "grammar",
            syntax = "lark",
            definition = ApplyPatchGrammar,
        ),
    )
}

public actual class ApplyPatchTool actual constructor(
    private val client: ApplyPatchToolClient,
) : Tool {
    public actual override val spec: ToolSpec = ApplyPatchTools.spec

    public actual override fun close(): Unit = Unit

    public actual override suspend fun handle(pending: PendingToolCall): AgentCommit.CompletedTool {
        currentCoroutineContext().ensureActive()
        return try {
            when (pending) {
                is PendingFunctionToolCall -> {
                    val message = "apply_patch received function-call JSON payload"
                    StableTextTool(
                        callId = pending.callId,
                        itemId = pending.itemId,
                        name = pending.name,
                        namespace = pending.namespace,
                        arguments = pending.arguments,
                        result = message,
                        success = false,
                    )
                }

                is PendingPatch -> handlePatch(pending)

                else -> error("apply_patch requires a parsed pending patch event.")
            }.also { currentCoroutineContext().ensureActive() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            throw failure
        }
    }

    private suspend fun handlePatch(
        pending: PendingPatch,
    ): AgentCommit.CompletedTool {
        return try {
            val result = client.apply(pending.diff)
            StablePatch(
                callId = pending.callId,
                itemId = pending.itemId,
                diff = pending.diff,
                result = StablePatchToolExecutionResult.Success(result),
            )
        } catch (error: IllegalArgumentException) {
            val message = error.message ?: "apply_patch failed"
            StablePatch(
                callId = pending.callId,
                itemId = pending.itemId,
                diff = pending.diff,
                result = StablePatchToolExecutionResult.Failure(message),
            )
        }
    }
}
