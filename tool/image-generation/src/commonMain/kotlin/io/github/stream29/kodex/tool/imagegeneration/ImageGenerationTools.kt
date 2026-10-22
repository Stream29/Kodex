@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.tool.imagegeneration

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableImageGenerationResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableImageGenerationToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingImageGenerationToolEvent
import io.github.stream29.kodex.openai.ResponsesApiNamespace
import io.github.stream29.kodex.openai.ResponsesApiTool
import io.github.stream29.kodex.openai.ToolSpec
import io.github.stream29.kodex.tool.contract.Tool
import io.github.stream29.kodex.tool.contract.typedTool
import kotlinx.coroutines.CancellationException
import kotlinx.io.files.Path

internal actual object ImageGenerationToolImplementation {
    public actual val spec: ToolSpec =
        ResponsesApiNamespace(
            name = ImageGenNamespace,
            description = ImageGenerationTools.DefaultNamespaceDescription,
            tools = listOf(
                ResponsesApiTool(
                    name = ImageGenToolName,
                    description = ImageGenerationTools.ImageGenDescription,
                    strict = false,
                    parameters = ImageGenParametersSchema,
                ),
            ),
        )

    public actual fun createTool(
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

    public actual fun outputDirectory(kodexHome: Path, sessionId: String): Path =
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
