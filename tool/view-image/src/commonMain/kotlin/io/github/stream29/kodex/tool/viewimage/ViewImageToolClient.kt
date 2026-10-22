@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.tool.viewimage

import io.github.stream29.kodex.utils.images.PromptImageMode
import io.github.stream29.kodex.utils.images.PromptImageTransformer
import io.github.stream29.kodex.utils.images.codec.readPromptImage
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.io.files.Path

public actual class ViewImageToolClient actual constructor(
    private val workingDirectoryProvider: suspend () -> Path,
    private val fileSystem: CoroutineFileSystem,
    private val transformer: PromptImageTransformer,
    private val canRequestOriginalImageDetail: Boolean,
) {
    public actual suspend fun view(arguments: ViewImageToolArguments): ViewImageToolOutput {
        if (arguments.path.isBlank()) {
            throw ViewImageToolException("`path` must not be blank")
        }
        if (arguments.environmentId != null) {
            throw ViewImageToolException("`environment_id` must be resolved before invoking the local view_image client")
        }

        val path = resolvePath(arguments.path)
        val metadata = fileSystem.metadataOrNull(path)
            ?: throw ViewImageToolException("image file does not exist: ${arguments.path}")
        if (!metadata.isRegularFile) {
            throw ViewImageToolException("image path is not a regular file: ${arguments.path}")
        }

        val useOriginal = canRequestOriginalImageDetail && arguments.detail == ViewImageDetail.Original
        val mode = if (useOriginal) PromptImageMode.Original else PromptImageMode.ResizeToFit
        val detail = if (useOriginal) ViewImageDetail.Original else ViewImageDetail.High
        val image = fileSystem.readPromptImage(path, mode, transformer)
        return ViewImageToolOutput(
            imageUrl = image.toDataUrl(),
            detail = detail,
        )
    }

    private suspend fun resolvePath(path: String): Path {
        val candidate = Path(path)
        return if (candidate.isAbsolute) candidate else Path(workingDirectoryProvider(), path)
    }
}
