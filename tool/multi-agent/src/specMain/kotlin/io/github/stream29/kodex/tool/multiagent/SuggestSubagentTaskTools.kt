package io.github.stream29.kodex.tool.multiagent

import io.github.stream29.kodex.openai.ResponsesApiTool

/** Static model-facing schema for the host-owned task suggestion tool. */
public object SuggestSubagentTaskTools {
    public const val Name: String = "suggest_subagent_task"

    public const val Description: String =
        "Suggest a batch of independent tasks for the user to review. The user must accept before any new ordinary Sessions are created. On acceptance, the tasks start without the source Session history, the prompts are submitted asynchronously, and this tool returns Session metadata immediately; accepted does not mean the tasks are complete and their results are not returned automatically. On rejection, no Session is created and optional user feedback is returned."

    public val spec: ResponsesApiTool =
        ResponsesApiTool(
            name = Name,
            description = Description,
            strict = false,
            parameters = SuggestSubagentTaskParametersSchema,
        )
}
