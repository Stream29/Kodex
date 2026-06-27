package io.github.stream29.codex.lite.tool.imagegeneration

import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.ResponsesApiNamespace
import io.github.stream29.codex.lite.openai.ResponsesApiTool
import io.github.stream29.codex.lite.openai.ToolSpec
import io.github.stream29.codex.lite.tool.builder.jsonTool
import io.github.stream29.codex.lite.tool.builder.jsonToolFailure
import io.github.stream29.codex.lite.tool.builder.jsonToolSuccess
import io.github.stream29.codex.lite.tool.contract.Tool

public const val ImageGenNamespace: String = "image_gen"
public const val ImageGenToolName: String = "imagegen"
public val ImageGenDefaultModel: OpenAiModelId = OpenAiModelId("gpt-image-2")
public const val ImageGenMaxEditImages: Int = 5

public object ImageGenerationTools {
    public const val DefaultNamespaceDescription: String = "Tools in the image_gen namespace."

    public const val ImageGenDescription: String =
        "The `image_gen.imagegen` tool enables image generation from descriptions and editing of existing images based on specific instructions. " +
            "Omit both `referenced_image_paths` and `num_last_images_to_include` when generating a brand new image. " +
            "For edits, use `referenced_image_paths` when every target image has a local file path. " +
            "Use `num_last_images_to_include` only when at least one target image has no local file path. " +
            "Never provide both `referenced_image_paths` and `num_last_images_to_include`."

    public val spec: ToolSpec =
        ResponsesApiNamespace(
            name = ImageGenNamespace,
            description = DefaultNamespaceDescription,
            tools = listOf(
                ResponsesApiTool(
                    name = ImageGenToolName,
                    description = ImageGenDescription,
                    strict = false,
                    parameters = ImageGenParametersSchema,
                ),
            ),
        )

    public fun createTool(client: ImageGenerationToolClient): Tool =
        jsonTool(
            spec = spec,
            inputDeserializer = ImageGenToolArguments.serializer(),
            outputSerializer = GeneratedImageOutput.serializer(),
        ) { arguments ->
            try {
                jsonToolSuccess(client.run(arguments))
            } catch (error: ImageGenerationToolException) {
                jsonToolFailure(error.message ?: "image_generation failed")
            }
        }
}
