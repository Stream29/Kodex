package io.github.stream29.kodex.tool.requestuserinput

import io.github.stream29.kodex.openai.ResponsesApiTool

/** Static model-facing schema for the host-owned `request_user_input` tool. */
public object RequestUserInputTools {
    public const val Name: String = "request_user_input"

    public const val Description: String =
        "Request user input for one to three short questions and wait for the response. " +
            "Set autoResolutionMs, from 60000 to 240000 milliseconds, only when the question is useful but non-blocking and continuing with best judgment is acceptable if the user does not answer; omit it when explicit user input is required."

    public val spec: ResponsesApiTool =
        ResponsesApiTool(
            name = Name,
            description = Description,
            strict = false,
            parameters = RequestUserInputParametersSchema,
        )
}
