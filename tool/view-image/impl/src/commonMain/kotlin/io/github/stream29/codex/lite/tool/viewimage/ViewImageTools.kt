package io.github.stream29.codex.lite.tool.viewimage

import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableImageViewResult
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableImageViewToolEvent
import io.github.stream29.codex.lite.openai.FunctionCallOutputBody
import io.github.stream29.codex.lite.openai.FunctionCallOutputContentItem
import io.github.stream29.codex.lite.openai.FunctionCallOutputPayload
import io.github.stream29.codex.lite.openai.ImageDetail
import io.github.stream29.codex.lite.openai.ResponsesApiTool
import io.github.stream29.codex.lite.tool.builder.functionOutputTool
import io.github.stream29.codex.lite.tool.contract.Tool

public object ViewImageTools {
    public const val Name: String = "view_image"

    public const val Description: String =
        "View a local image file from the filesystem when visual inspection is needed. Use this for images already available on disk."

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
        functionOutputTool(
            spec = toolSpec(options),
            inputDeserializer = ViewImageToolArguments.serializer(),
        ) { _, arguments ->
            try {
                val output = client.view(arguments)
                FunctionCallOutputPayload(
                    body = FunctionCallOutputBody.ContentItems(
                        listOf(
                            FunctionCallOutputContentItem.InputImage(
                                imageUrl = output.imageUrl,
                                detail = when (output.detail) {
                                    ViewImageDetail.High -> ImageDetail.High
                                    ViewImageDetail.Original -> ImageDetail.Original
                                },
                            ),
                        ),
                    ),
                    success = true,
                ) to StableImageViewToolEvent(
                    arguments = arguments,
                    result = StableImageViewResult.Success(output),
                )
            } catch (error: ViewImageToolException) {
                val message = error.message ?: "view_image failed"
                FunctionCallOutputPayload(
                    body = FunctionCallOutputBody.Text(message),
                    success = false,
                ) to StableImageViewToolEvent(
                    arguments = arguments,
                    result = StableImageViewResult.Failure(message),
                )
            }
        }
}
