package io.github.stream29.kodex.tool.currenttime

import io.github.stream29.kodex.openai.ResponsesApiNamespace
import io.github.stream29.kodex.openai.ResponsesApiTool
import io.github.stream29.kodex.openai.ToolSpec
import io.github.stream29.kodex.tool.builder.jsonToolSuccess
import io.github.stream29.kodex.tool.builder.textTool
import io.github.stream29.kodex.tool.contract.Tool
import kotlinx.serialization.builtins.serializer

public const val CurrentTimeNamespace: String = "clock"
public const val CurrentTimeToolName: String = "curr_time"

public object CurrentTimeTools {
    public const val NamespaceDescription: String = "Tools for reading and waiting on time."
    public const val Description: String = "Return the current time in UTC."

    public val spec: ToolSpec =
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

    public fun createTool(client: CurrentTimeToolClient = CurrentTimeToolClient()): Tool =
        textTool(
            spec = spec,
            inputDeserializer = Unit.serializer(),
        ) {
            jsonToolSuccess("It is ${client.currentTime()}.")
        }
}
