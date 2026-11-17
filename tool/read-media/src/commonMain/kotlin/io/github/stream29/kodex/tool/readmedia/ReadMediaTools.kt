@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.tool.readmedia

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableReadMediaResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableReadMedia
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingReadMedia
import io.github.stream29.kodex.openai.ResponsesApiTool
import io.github.stream29.kodex.tool.contract.Tool
import io.github.stream29.kodex.tool.contract.typedTool

internal actual object ReadMediaToolImplementation {
    public actual val spec: ResponsesApiTool =
        ResponsesApiTool(
            name = ReadMediaTools.Name,
            description = ReadMediaTools.Description,
            strict = false,
            parameters = readMediaParametersSchema(),
        )

    public actual fun createTool(
        client: ReadMediaToolClient,
    ): Tool =
        typedTool(
            spec = spec,
            select = { it as? PendingReadMedia },
        ) { pending ->
            try {
                val output = client.read(pending.arguments)
                StableReadMedia(
                    callId = pending.callId,
                    itemId = pending.itemId,
                    arguments = pending.arguments,
                    result = StableReadMediaResult.Success(output),
                )
            } catch (error: ReadMediaToolException) {
                val message = error.message ?: "read_media failed"
                StableReadMedia(
                    callId = pending.callId,
                    itemId = pending.itemId,
                    arguments = pending.arguments,
                    result = StableReadMediaResult.Failure(message),
                )
            }
        }
}
