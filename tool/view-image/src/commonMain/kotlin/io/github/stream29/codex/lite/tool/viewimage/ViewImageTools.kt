package io.github.stream29.codex.lite.tool.viewimage

import io.github.stream29.codex.lite.tool.contract.ResponsesApiTool

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
}
