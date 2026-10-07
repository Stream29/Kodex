package io.github.stream29.kodex.tool.requestuserinput

import io.github.stream29.kodex.openai.ResponsesApiTool

/** Static model-facing schema for the host-owned `request_user_input` tool. */
public object RequestUserInputTools {
    public const val Name: String = "request_user_input"

    public const val Description: String =
        "Request user input for one to three short questions and wait for the response. " +
            "This host always waits for explicit user input and does not generate timeout answers. " +
            "Omit autoResolutionMs; it is retained as wire-compatible metadata, not an implemented auto-resolution timer."

    public val spec: ResponsesApiTool =
        ResponsesApiTool(
            name = Name,
            description = Description,
            strict = false,
            parameters = RequestUserInputParametersSchema,
        )
}
