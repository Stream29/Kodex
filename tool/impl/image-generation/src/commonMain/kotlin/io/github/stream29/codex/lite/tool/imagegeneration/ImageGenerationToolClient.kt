package io.github.stream29.codex.lite.tool.imagegeneration

import io.github.stream29.codex.lite.openai.ImageBackground
import io.github.stream29.codex.lite.openai.ImageEditRequest
import io.github.stream29.codex.lite.openai.ImageGenerationRequest
import io.github.stream29.codex.lite.openai.ImageQuality
import io.github.stream29.codex.lite.openai.ImageUrl
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.OpenAiErrorResponse
import io.github.stream29.codex.lite.openai.OpenAiResult
import io.github.stream29.codex.lite.openai.client.contract.OpenAiClient
import io.github.stream29.codex.lite.utils.images.PromptImageMode
import io.github.stream29.codex.lite.utils.images.PromptImageTransformer
import io.github.stream29.codex.lite.utils.images.codec.HostPromptImageTransformer
import io.github.stream29.codex.lite.utils.images.codec.readPromptImage
import io.github.stream29.codex.lite.utils.images.toDataUrl
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path

public class ImageGenerationToolClient(
    private val client: OpenAiClient,
    private val root: Path = Path("."),
    private val fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    private val transformer: PromptImageTransformer = HostPromptImageTransformer,
    private val model: OpenAiModelId = ImageGenDefaultModel,
) {
    public suspend fun run(arguments: ImageGenToolArguments): GeneratedImageOutput {
        val request = requestFor(arguments)
        val response = when (request) {
            is ImageToolRequest.Generate -> client.generateImage(request.value)
            is ImageToolRequest.Edit -> client.editImage(request.value)
        }
        val result = response.successOrThrow().data.firstOrNull()?.b64Json
            ?: throw ImageGenerationToolException("image generation returned no image data")
        return GeneratedImageOutput(result = result)
    }

    private fun <T> OpenAiResult<T, OpenAiErrorResponse>.successOrThrow(): T =
        when (this) {
            is OpenAiResult.Success -> value
            is OpenAiResult.Failure -> throw ImageGenerationToolException(
                "image generation failed: ${error.messageText ?: error.toString()}",
            )
        }

    private suspend fun requestFor(arguments: ImageGenToolArguments): ImageToolRequest {
        if (arguments.prompt.isBlank()) {
            throw ImageGenerationToolException("`prompt` must not be blank")
        }

        val paths = arguments.referencedImagePaths.orEmpty()
        if (paths.size > ImageGenMaxEditImages) {
            throw ImageGenerationToolException("`referenced_image_paths` must contain at most $ImageGenMaxEditImages paths")
        }

        return when {
            paths.isEmpty() && arguments.numLastImagesToInclude == null -> {
                ImageToolRequest.Generate(
                    ImageGenerationRequest(
                        prompt = arguments.prompt,
                        background = ImageBackground.Auto,
                        model = model,
                        n = null,
                        quality = ImageQuality.Auto,
                        size = "auto",
                    ),
                )
            }
            paths.isNotEmpty() && arguments.numLastImagesToInclude == null -> {
                ImageToolRequest.Edit(
                    ImageEditRequest(
                        images = paths.map { ImageUrl(loadReferencedImage(it)) },
                        prompt = arguments.prompt,
                        background = ImageBackground.Auto,
                        model = model,
                        n = null,
                        quality = ImageQuality.Auto,
                        size = "auto",
                    ),
                )
            }
            paths.isEmpty() && arguments.numLastImagesToInclude != null -> {
                validateConversationImageCount(arguments.numLastImagesToInclude)
                throw ImageGenerationToolException("`num_last_images_to_include` requires conversation history from the agent loop")
            }
            else -> {
                throw ImageGenerationToolException(
                    "provide only one of `referenced_image_paths` or `num_last_images_to_include`",
                )
            }
        }
    }

    private fun validateConversationImageCount(count: Long) {
        if (count !in 1L..ImageGenMaxEditImages.toLong()) {
            throw ImageGenerationToolException("`num_last_images_to_include` must be between 1 and $ImageGenMaxEditImages")
        }
    }

    private suspend fun loadReferencedImage(path: String): String {
        if (path.isBlank()) {
            throw ImageGenerationToolException("`referenced_image_paths` must not contain blank paths")
        }
        val image = fileSystem.readPromptImage(resolvePath(path), PromptImageMode.Original, transformer)
        return image.toDataUrl()
    }

    private fun resolvePath(path: String): Path {
        val candidate = Path(path)
        return if (candidate.isAbsolute) candidate else Path(root, path)
    }

    private sealed interface ImageToolRequest {
        data class Generate(val value: ImageGenerationRequest) : ImageToolRequest
        data class Edit(val value: ImageEditRequest) : ImageToolRequest
    }
}
