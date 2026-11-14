package io.github.stream29.kodex.tool.awaitevent

import io.github.stream29.kodex.openai.ResponsesApiTool

/** Model-facing tool metadata and schema for `await_event`. */
public object AwaitEventTools {
    public const val Name: String = "await_event"

    public const val Description: String =
        "Suspend execution without holding compute resources until a user interaction (ask_user), " +
            "direct user message, scheduled task, or any terminal listed in `terminals` completes."

    public val spec: ResponsesApiTool =
        ResponsesApiTool(
            name = Name,
            description = Description,
            strict = false,
            parameters = AwaitEventParametersSchema,
        )
}
