@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.tool.currenttime

import io.github.stream29.kodex.openai.ResponsesApiNamespace
import io.github.stream29.kodex.openai.ResponsesApiTool
import io.github.stream29.kodex.openai.ToolSpec
import io.github.stream29.kodex.tool.builder.jsonToolSuccess
import io.github.stream29.kodex.tool.builder.textTool
import io.github.stream29.kodex.tool.contract.Tool
import io.github.stream29.kodex.tool.currenttime.CurrentTimeTools.Description
import io.github.stream29.kodex.tool.currenttime.CurrentTimeTools.NamespaceDescription
import kotlinx.serialization.builtins.serializer

internal actual object CurrentTimeToolImplementation {
    public actual val spec: ToolSpec =
        ResponsesApiNamespace(
            name = CurrentTimeNamespace,
            description = NamespaceDescription,
            tools = listOf(
                ResponsesApiTool(
                    name = CurrentTimeToolName,
                    description = Description,
                    strict = false,
                    parameters = CurrentTimeParametersSchema,
                    outputSchema = CurrentTimeOutputSchema,
                ),
            ),
        )

    public actual fun createTool(client: CurrentTimeToolClient): Tool =
        textTool(
            spec = spec,
            inputDeserializer = Unit.serializer(),
        ) {
            jsonToolSuccess("It is ${client.currentTime()}.")
        }
}
