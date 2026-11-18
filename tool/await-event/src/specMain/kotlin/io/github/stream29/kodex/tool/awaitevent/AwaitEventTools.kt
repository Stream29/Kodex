package io.github.stream29.kodex.tool.awaitevent

import io.github.stream29.kodex.openai.ResponsesApiTool

/** Model-facing tool metadata and schema for `await_event`. */
public object AwaitEventTools {
    public const val Name: String = "await_event"

    public const val Description: String =
        "Suspend until any event in `events` happens. Direct user messages, ask_user answers, " +
            "and schedules can also wake the agent when not listed. When waiting only for one terminal, include a timeout."

    public val spec: ResponsesApiTool =
        ResponsesApiTool(
            name = Name,
            description = Description,
            strict = false,
            parameters = AwaitEventParametersSchema,
        )
}
