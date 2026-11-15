@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.tool.imagegeneration

import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.utils.images.PromptImageTransformer
import io.github.stream29.kodex.utils.images.codec.HostPromptImageTransformer
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path

/**
 * Backend image-generation client used by `AgentRuntimeRpc.generateImage` and `kodex image`.
 */
public expect class ImageGenerationToolClient(
    client: OpenAiClient,
    workingDirectoryProvider: suspend () -> Path = { Path(".") },
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    transformer: PromptImageTransformer = HostPromptImageTransformer,
    model: OpenAiModelId = ImageGenDefaultModel,
) {
    public suspend fun run(arguments: ImageGenToolArguments): GeneratedImageOutput

    /** Generates an image and writes the decoded PNG under [outputDirectory]. */
    public suspend fun generateToFile(
        arguments: ImageGenToolArguments,
        outputDirectory: Path,
    ): GeneratedImageArtifact
}

/** Resolves the session-specific image artifact directory under `<kodexHome>/media/generated/<sessionId>`. */
public expect fun imageArtifactDirectory(kodexHome: Path, sessionId: String): Path
