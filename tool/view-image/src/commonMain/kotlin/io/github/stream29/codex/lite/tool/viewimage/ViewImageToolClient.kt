package io.github.stream29.codex.lite.tool.viewimage

import io.github.stream29.codex.lite.utils.images.PromptImageMode
import io.github.stream29.codex.lite.utils.images.PromptImageTransformer
import io.github.stream29.codex.lite.utils.images.codec.HostPromptImageTransformer
import io.github.stream29.codex.lite.utils.images.codec.readPromptImage
import io.github.stream29.codex.lite.utils.images.toDataUrl
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path

public class ViewImageToolClient(
    private val root: Path = Path("."),
    private val fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    private val transformer: PromptImageTransformer = HostPromptImageTransformer,
    private val canRequestOriginalImageDetail: Boolean = false,
) {
    public suspend fun view(arguments: ViewImageToolArguments): ViewImageToolOutput {
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

    private fun resolvePath(path: String): Path {
        val candidate = Path(path)
        return if (candidate.isAbsolute) candidate else Path(root, path)
    }
}
