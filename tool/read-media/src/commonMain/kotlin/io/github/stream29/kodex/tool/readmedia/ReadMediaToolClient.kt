@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.tool.readmedia

import io.github.stream29.kodex.utils.images.PromptImageMode
import io.github.stream29.kodex.utils.images.PromptImageTransformer
import io.github.stream29.kodex.utils.images.codec.readPromptImage
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.io.files.Path

public actual class ReadMediaToolClient actual constructor(
    private val workingDirectoryProvider: suspend () -> Path,
    private val fileSystem: CoroutineFileSystem,
    private val transformer: PromptImageTransformer,
    private val canRequestOriginalImageDetail: Boolean,
) {
    public actual suspend fun read(arguments: ReadMediaToolArguments): ReadMediaToolOutput {
        if (arguments.path.isBlank()) {
            throw ReadMediaToolException("`path` must not be blank")
        }

        val path = resolvePath(arguments.path)
        val metadata = fileSystem.metadataOrNull(path)
            ?: throw ReadMediaToolException("media file does not exist: ${arguments.path}")
        if (!metadata.isRegularFile) {
            throw ReadMediaToolException("media path is not a regular file: ${arguments.path}")
        }

        val useOriginal = canRequestOriginalImageDetail && arguments.detail == MediaDetail.Original
        val mode = if (useOriginal) PromptImageMode.Original else PromptImageMode.ResizeToFit
        val detail = if (useOriginal) MediaDetail.Original else MediaDetail.High
        val image = try {
            fileSystem.readPromptImage(path, mode, transformer)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ReadMediaToolException) {
            throw error
        } catch (error: Exception) {
            throw ReadMediaToolException(error.message ?: "failed to read media file: ${arguments.path}")
        }
        return ReadMediaToolOutput(
            imageUrl = image.toDataUrl(),
            detail = detail,
        )
    }

    private suspend fun resolvePath(path: String): Path {
        val candidate = Path(path)
        return if (candidate.isAbsolute) candidate else Path(workingDirectoryProvider(), path)
    }
}
