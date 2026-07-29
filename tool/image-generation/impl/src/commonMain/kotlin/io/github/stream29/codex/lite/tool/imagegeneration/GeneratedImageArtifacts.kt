package io.github.stream29.codex.lite.tool.imagegeneration

import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlin.io.encoding.Base64

private const val MaximumOutputHintByteCount: Int = 1024

internal suspend fun GeneratedImageOutput.persistGeneratedImage(
    outputDirectory: Path,
    callId: String,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): GeneratedImageOutput {
    val outputPath = Path(
        outputDirectory,
        "${callId.toImageArtifactPathSegment()}.png",
    )
    val outputDirectory = requireNotNull(outputPath.parent)
    fileSystem.createDirectories(outputDirectory)
    fileSystem.writeBytes(outputPath, Base64.decode(result.trim()))

    val hint = "Generated images are saved to $outputDirectory as $outputPath by default.\n" +
        "If you need to use a generated image at another path, copy it and leave the original " +
        "in place unless the user explicitly asks you to delete it."
    return copy(
        outputHint = hint.takeIf {
            it.encodeToByteArray().size <= MaximumOutputHintByteCount
        },
    )
}
