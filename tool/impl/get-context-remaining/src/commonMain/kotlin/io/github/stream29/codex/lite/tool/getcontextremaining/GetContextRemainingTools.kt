package io.github.stream29.codex.lite.tool.getcontextremaining

import io.github.stream29.codex.lite.openai.ResponsesApiTool
import io.github.stream29.codex.lite.tool.builder.jsonToolSuccess
import io.github.stream29.codex.lite.tool.builder.textTool
import io.github.stream29.codex.lite.tool.contract.Tool
import kotlinx.serialization.builtins.serializer

/** Static model-facing schema and factory for `get_context_remaining`. */
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

    /**
     * Creates the Direct-mode text tool using the runtime's current budget query.
     *
     * @param getTokensUntilCompaction Returns `null` when the provider has not
     * reported active-context usage or no bounded context budget is known.
     */
    public fun createTool(
        getTokensUntilCompaction: suspend () -> Long?,
    ): Tool =
        textTool(
            spec = spec,
            inputDeserializer = Unit.serializer(),
        ) {
            jsonToolSuccess(getTokensUntilCompaction().renderTokenBudgetRemaining())
        }
}

private fun Long?.renderTokenBudgetRemaining(): String =
    if (this == null) {
        "You have unknown tokens left in this context window."
    } else {
        "You have $this tokens left in this context window."
    }
