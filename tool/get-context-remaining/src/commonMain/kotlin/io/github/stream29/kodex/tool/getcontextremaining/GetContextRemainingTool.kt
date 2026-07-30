package io.github.stream29.kodex.tool.getcontextremaining

import io.github.stream29.kodex.agentstate.contextwindow.tokensUntilCompaction
import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.openai.modelcatalog.OpenAiModelCatalog
import io.github.stream29.kodex.tool.builder.jsonToolSuccess
import io.github.stream29.kodex.tool.builder.textTool
import io.github.stream29.kodex.tool.contract.Tool
import kotlinx.serialization.builtins.serializer

/** Creates `get_context_remaining` bound to this agent's current snapshot. */
public fun KodexAgentState.getContextRemainingTool(
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
