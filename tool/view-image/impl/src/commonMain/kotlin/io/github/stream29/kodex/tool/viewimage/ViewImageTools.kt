package io.github.stream29.kodex.tool.viewimage

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableImageViewResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableImageViewToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingImageViewToolEvent
import io.github.stream29.kodex.openai.ResponsesApiTool
import io.github.stream29.kodex.tool.contract.Tool
import io.github.stream29.kodex.tool.contract.typedTool

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
        typedTool(
            spec = toolSpec(options),
            select = { it as? PendingImageViewToolEvent },
        ) { pending ->
            try {
                val output = client.view(pending.arguments)
                StableImageViewToolEvent(
                    callId = pending.callId,
                    itemId = pending.itemId,
                    arguments = pending.arguments,
                    result = StableImageViewResult.Success(output),
                )
            } catch (error: ViewImageToolException) {
                val message = error.message ?: "view_image failed"
                StableImageViewToolEvent(
                    callId = pending.callId,
                    itemId = pending.itemId,
                    arguments = pending.arguments,
                    result = StableImageViewResult.Failure(message),
                )
            }
        }
}
