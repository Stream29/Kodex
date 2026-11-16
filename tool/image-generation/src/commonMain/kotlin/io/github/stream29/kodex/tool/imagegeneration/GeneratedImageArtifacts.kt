package io.github.stream29.kodex.tool.imagegeneration

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlin.io.encoding.Base64

private const val MaximumOutputHintByteCount: Int = 1024

public actual fun imageArtifactDirectory(kodexHome: Path, sessionId: String): Path =
    Path(kodexHome, "media", "generated", sessionId.toImageArtifactPathSegment())

internal suspend fun GeneratedImageOutput.persistGeneratedImage(
    outputDirectory: Path,
    callId: String,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): GeneratedImageOutput {
    val outputPath = Path(
        outputDirectory,
        "${callId.toImageArtifactPathSegment()}.png",
    )
    val parentDirectory = requireNotNull(outputPath.parent)
    fileSystem.createDirectories(parentDirectory)
    fileSystem.writeBytes(outputPath, Base64.decode(result.trim()))

    val hint = "Generated images are saved to $parentDirectory as $outputPath by default.\n" +
        "If you need to use a generated image at another path, copy it and leave the original " +
        "in place unless the user explicitly asks you to delete it."
    return copy(
        outputHint = hint.takeIf {
            it.encodeToByteArray().size <= MaximumOutputHintByteCount
        },
    )
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
