@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.tool.viewimage

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableImageViewResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableImageViewToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingImageViewToolEvent
import io.github.stream29.kodex.openai.ResponsesApiTool
import io.github.stream29.kodex.tool.contract.Tool
import io.github.stream29.kodex.tool.contract.typedTool

internal actual object ViewImageToolImplementation {
    public actual val spec: ResponsesApiTool = toolSpec(ViewImageToolOptions())

    public actual fun toolSpec(options: ViewImageToolOptions): ResponsesApiTool =
        ResponsesApiTool(
            name = ViewImageTools.Name,
            description = ViewImageTools.Description,
            strict = false,
            parameters = viewImageParametersSchema(options),
            outputSchema = ViewImageOutputSchema,
        )

    public actual fun createTool(
        client: ViewImageToolClient,
        options: ViewImageToolOptions,
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
