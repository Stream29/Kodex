package io.github.stream29.kodex.tool.getcontextremaining

import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.openai.ResponsesApiTool
import io.github.stream29.kodex.openai.modelcatalog.OpenAiModelCatalogStore
import io.github.stream29.kodex.tool.contract.Tool

/** Static model-facing schema for `get_context_remaining`. */
public object GetContextRemainingTools {
    public const val Name: String = "get_context_remaining"
    public const val Description: String = "Get the remaining tokens in the current context window."

    public val spec: ResponsesApiTool =
        ResponsesApiTool(
            name = Name,
            description = Description,
            strict = false,
            parameters = GetContextRemainingParametersSchema,
            outputSchema = GetContextRemainingOutputSchema,
        )
}

/** Creates `get_context_remaining` bound to this agent's current snapshot. */
public expect fun KodexAgentState.getContextRemainingTool(
    modelCatalog: OpenAiModelCatalogStore,
): Tool
