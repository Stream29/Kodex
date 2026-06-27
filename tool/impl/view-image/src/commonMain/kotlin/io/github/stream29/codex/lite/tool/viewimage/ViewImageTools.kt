package io.github.stream29.codex.lite.tool.viewimage

import io.github.stream29.codex.lite.openai.ResponsesApiTool
import io.github.stream29.codex.lite.tool.builder.jsonTool
import io.github.stream29.codex.lite.tool.builder.jsonToolFailure
import io.github.stream29.codex.lite.tool.builder.jsonToolSuccess
import io.github.stream29.codex.lite.tool.contract.Tool

public object ViewImageTools {
    public const val Name: String = "view_image"

    public const val Description: String =
        "View a local image file and return a model-consumable image data URL."

    public val spec: ResponsesApiTool = toolSpec()

    public fun toolSpec(options: ViewImageToolOptions = ViewImageToolOptions()): ResponsesApiTool =
        ResponsesApiTool(
            name = Name,
            description = Description,
            strict = false,
            parameters = viewImageParametersSchema(options),
            outputSchema = ViewImageOutputSchema,
        )

    public fun createTool(
        client: ViewImageToolClient = ViewImageToolClient(),
        options: ViewImageToolOptions = ViewImageToolOptions(),
    ): Tool =
        jsonTool(
            spec = toolSpec(options),
            inputDeserializer = ViewImageToolArguments.serializer(),
            outputSerializer = ViewImageToolOutput.serializer(),
        ) { arguments ->
            try {
                jsonToolSuccess(client.view(arguments))
            } catch (error: ViewImageToolException) {
                jsonToolFailure(error.message ?: "view_image failed")
            }
        }
}
