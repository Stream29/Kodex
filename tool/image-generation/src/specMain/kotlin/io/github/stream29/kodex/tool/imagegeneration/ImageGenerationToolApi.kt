@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.tool.imagegeneration

import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ToolSpec
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.tool.contract.Tool
import io.github.stream29.kodex.utils.images.PromptImageTransformer
import io.github.stream29.kodex.utils.images.codec.HostPromptImageTransformer
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path

public expect class ImageGenerationToolClient(
    client: OpenAiClient,
    workingDirectoryProvider: suspend () -> Path = { Path(".") },
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    transformer: PromptImageTransformer = HostPromptImageTransformer,
    model: OpenAiModelId = ImageGenDefaultModel,
) {
    public suspend fun run(arguments: ImageGenToolArguments): GeneratedImageOutput
}

public object ImageGenerationTools {
    public const val DefaultNamespaceDescription: String = "Tools in the image_gen namespace."

    public const val ImageGenDescription: String =
        "The `image_gen.imagegen` tool enables image generation from descriptions and editing of existing images based on specific instructions. Use it when:\n" +
            "\n" +
            "- The user requests an image based on a scene description, such as a diagram, portrait, comic, meme, or any other visual.\n" +
            "- The user wants to modify an image available at a local file path, including adding or removing elements, altering colors, improving quality/resolution, or transforming the style.\n" +
            "\n" +
            "Guidelines:\n" +
            "- Omit both `referenced_image_paths` and `num_last_images_to_include` when generating a brand new image.\n" +
            "- For edits, use `referenced_image_paths` when every target image has a local file path.\n" +
            "- If you have not seen a local image yet, use `view_image` to inspect it before editing.\n" +
            "- Conversation-history image selection is not supported by this host. Omit `num_last_images_to_include`; that field is retained only for wire compatibility and a non-null value fails explicitly.\n" +
            "- Never provide both `referenced_image_paths` and `num_last_images_to_include`.\n" +
            "- If a target image has no accessible local file path, ask the user to provide one before editing.\n" +
            "- Directly generate the image without reconfirmation or clarification unless required images must be attached again.\n" +
            "- Always use this tool for image editing unless the user explicitly requests otherwise. Do not use the `python` tool for image editing unless specifically instructed.\n"

    public val spec: ToolSpec
        get() = ImageGenerationToolImplementation.spec

    /**
     * Creates the complete image-generation tool, including artifact persistence.
     *
     * @param outputDirectory Session-specific output directory.
     */
    public fun createTool(client: ImageGenerationToolClient, outputDirectory: Path): Tool =
        ImageGenerationToolImplementation.createTool(client, outputDirectory)

    /** Resolves the session-specific output directory under [kodexHome]. */
    public fun outputDirectory(kodexHome: Path, sessionId: String): Path =
        ImageGenerationToolImplementation.outputDirectory(kodexHome, sessionId)
}

internal expect object ImageGenerationToolImplementation {
    val spec: ToolSpec
    fun createTool(client: ImageGenerationToolClient, outputDirectory: Path): Tool
    fun outputDirectory(kodexHome: Path, sessionId: String): Path
}
