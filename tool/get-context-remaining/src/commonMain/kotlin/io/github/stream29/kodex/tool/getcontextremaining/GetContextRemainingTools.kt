package io.github.stream29.kodex.tool.getcontextremaining

import io.github.stream29.kodex.openai.ResponsesApiTool

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
