package io.github.stream29.codex.lite.tool.webrun

import io.github.stream29.codex.lite.openai.OpenAiResult
import io.github.stream29.codex.lite.openai.ResponsesApiNamespace
import io.github.stream29.codex.lite.openai.ResponsesApiTool
import io.github.stream29.codex.lite.openai.ToolSpec
import io.github.stream29.codex.lite.tool.builder.jsonToolFailure
import io.github.stream29.codex.lite.tool.builder.jsonToolSuccess
import io.github.stream29.codex.lite.tool.builder.textTool
import io.github.stream29.codex.lite.tool.contract.Tool

public const val WebRunNamespace: String = "web"
public const val WebRunToolName: String = "run"

public object WebRunTools {
    public const val NamespaceDescription: String = "Tools for accessing the internet."

    public val description: String = """
        Access the internet with search, navigation, image search, finance, weather, sports, and time commands.

        Combine independent commands in one call. Use `response_length` to control result size. Search queries accept at most four entries.
    """.trimIndent()

    public val spec: ToolSpec =
        ResponsesApiNamespace(
            name = WebRunNamespace,
            description = NamespaceDescription,
            tools = listOf(
                ResponsesApiTool(
                    name = WebRunToolName,
                    description = description,
                    strict = false,
                    parameters = WebRunParametersSchema,
                ),
            ),
        )

    public fun createTool(client: WebRunToolClient): Tool =
        textTool(
            spec = spec,
            inputDeserializer = io.github.stream29.codex.lite.openai.SearchCommands.serializer(),
        ) { commands ->
            when (val result = client.run(commands)) {
                is OpenAiResult.Success -> jsonToolSuccess(result.value.output)
                is OpenAiResult.Failure -> jsonToolFailure(
                    result.error.messageText ?: "web.run failed without an error message.",
                )
            }
        }
}
