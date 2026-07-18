package io.github.stream29.codex.lite.agentruntime.tool

import io.github.stream29.codex.lite.agentruntime.contextwindow.tokensUntilCompaction
import io.github.stream29.codex.lite.agentruntime.contract.CodexAgentRuntime
import io.github.stream29.codex.lite.openai.modelcatalog.OpenAiModelCatalog
import io.github.stream29.codex.lite.tool.contract.Tool
import io.github.stream29.codex.lite.tool.getcontextremaining.GetContextRemainingTools

/** Creates `get_context_remaining` bound to this runtime's current snapshot. */
public fun CodexAgentRuntime.getContextRemainingTool(
    modelCatalog: OpenAiModelCatalog,
): Tool =
    GetContextRemainingTools.createTool {
        tokensUntilCompaction(modelCatalog)
    }
