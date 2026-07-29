package io.github.stream29.codex.lite.tool.getcontextremaining

import io.github.stream29.codex.lite.agentstate.contextwindow.tokensUntilCompaction
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState
import io.github.stream29.codex.lite.openai.modelcatalog.OpenAiModelCatalog
import io.github.stream29.codex.lite.tool.builder.jsonToolSuccess
import io.github.stream29.codex.lite.tool.builder.textTool
import io.github.stream29.codex.lite.tool.contract.Tool
import kotlinx.serialization.builtins.serializer

/** Creates `get_context_remaining` bound to this agent's current snapshot. */
public fun CodexAgentState.getContextRemainingTool(
    modelCatalog: OpenAiModelCatalog,
): Tool = textTool(
    spec = GetContextRemainingTools.spec,
    inputDeserializer = Unit.serializer(),
) {
    jsonToolSuccess(
        tokensUntilCompaction(modelCatalog).let { tokens ->
            if (tokens == null) {
                "You have unknown tokens left in this context window."
            } else {
                "You have $tokens tokens left in this context window."
            }
        },
    )
}
