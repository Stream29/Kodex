package io.github.stream29.kodex.tool.plan

import io.github.stream29.kodex.openai.ResponsesApiTool

public object PlanTools {
    public const val Name: String = "update_plan"

    public const val Description: String =
        "Updates the task plan.\n" +
            "Provide an optional explanation and a list of plan items, each with a step and status.\n" +
            "At most one step can be in_progress at a time.\n"

    public val spec: ResponsesApiTool =
        ResponsesApiTool(
            name = Name,
            description = Description,
            strict = false,
            parameters = UpdatePlanParametersSchema,
        )
}
