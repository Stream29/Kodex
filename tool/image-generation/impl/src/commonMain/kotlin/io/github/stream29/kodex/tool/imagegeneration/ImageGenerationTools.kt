package io.github.stream29.kodex.tool.imagegeneration

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableImageGenerationResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableImageGenerationToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingImageGenerationToolEvent
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ResponsesApiNamespace
import io.github.stream29.kodex.openai.ResponsesApiTool
import io.github.stream29.kodex.openai.ToolSpec
import io.github.stream29.kodex.tool.contract.Tool
import io.github.stream29.kodex.tool.contract.typedTool
import kotlinx.coroutines.CancellationException
import kotlinx.io.files.Path

public const val ImageGenNamespace: String = "image_gen"
public const val ImageGenToolName: String = "imagegen"
public val ImageGenDefaultModel: OpenAiModelId = OpenAiModelId("gpt-image-2")
public const val ImageGenMaxEditImages: Int = 5

public object ImageGenerationTools {
    public const val DefaultNamespaceDescription: String = "Tools in the image_gen namespace."

    public const val ImageGenDescription: String =
        "The `image_gen.imagegen` tool enables image generation from descriptions and editing of existing images based on specific instructions. Use it when:\n" +
            "\n" +
            "- The user requests an image based on a scene description, such as a diagram, portrait, comic, meme, or any other visual.\n" +
            "- The user wants to modify an attached or previously generated image with specific changes, including adding or removing elements, altering colors, improving quality/resolution, or transforming the style (e.g., cartoon, oil painting).\n" +
            "\n" +
            "Guidelines:\n" +
            "- In code mode, pass the result to `generatedImage(result)`.\n" +
            "- Omit both `referenced_image_paths` and `num_last_images_to_include` when generating a brand new image.\n" +
            "- For edits, use `referenced_image_paths` when every target image has a local file path.\n" +
            "- If you have not seen a local image yet, use `view_image` to inspect it before editing.\n" +
            "- Use `num_last_images_to_include` only when at least one target image has no local file path.\n" +
            "- Set `num_last_images_to_include` to the smallest number of recent conversation images that includes every target image, up to 5.\n" +
            "- Never provide both `referenced_image_paths` and `num_last_images_to_include`.\n" +
            "- If neither mechanism can include every target image, ask the user to attach the missing images again.\n" +
            "- Directly generate the image without reconfirmation or clarification unless required images must be attached again.\n" +
            "- After each image generation, do not mention anything related to download. Do not summarize the image. Do not ask followup question. Do not say ANYTHING after you generate an image.\n" +
            "- Always use this tool for image editing unless the user explicitly requests otherwise. Do not use the `python` tool for image editing unless specifically instructed.\n"

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

    /**
     * Creates the complete image-generation tool, including artifact persistence.
     *
     * @param outputDirectory Session-specific output directory.
     */
    public fun createTool(
        client: ImageGenerationToolClient,
        outputDirectory: Path,
    ): Tool =
        typedTool(
            spec = spec,
            select = { it as? PendingImageGenerationToolEvent },
        ) { pending ->
            try {
                val output = client.run(pending.arguments)
                var savedPath: String? = null
                val persistedOutput = try {
                    output.persistGeneratedImage(
                        outputDirectory = outputDirectory,
                        callId = pending.callId,
                    ).also {
                        savedPath = Path(
                            outputDirectory,
                            "${pending.callId.toImageArtifactPathSegment()}.png",
                        ).toString()
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    output
                }
                StableImageGenerationToolEvent(
                    callId = pending.callId,
                    itemId = pending.itemId,
                    arguments = pending.arguments,
                    result = StableImageGenerationResult.Success(
                        output = persistedOutput,
                        savedPath = savedPath,
                    ),
                )
            } catch (error: ImageGenerationToolException) {
                val message = error.message ?: "image_generation failed"
                StableImageGenerationToolEvent(
                    callId = pending.callId,
                    itemId = pending.itemId,
                    arguments = pending.arguments,
                    result = StableImageGenerationResult.Failure(message),
                )
            }
        }

    /** Resolves the session-specific output directory under [kodexHome]. */
    public fun outputDirectory(kodexHome: Path, sessionId: String): Path =
        Path(kodexHome, "generated_images", sessionId.toImageArtifactPathSegment())

}

internal fun String.toImageArtifactPathSegment(): String =
    map { character ->
        if (character.isLetterOrDigit() && character.code < 128 || character == '-' || character == '_') {
            character
        } else {
            '_'
        }
    }
        .joinToString(separator = "")
        .ifEmpty { "generated_image" }
