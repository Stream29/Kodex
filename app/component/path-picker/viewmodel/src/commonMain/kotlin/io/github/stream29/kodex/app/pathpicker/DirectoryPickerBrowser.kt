package io.github.stream29.kodex.app.pathpicker

import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerFailure
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerBrowser
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerListing
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerLoadResult
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerValidationResult
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.osenvironment.userHomeDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemPathSeparator

/** Resolves and lists directory choices without depending on a frontend renderer. */
internal class SystemDirectoryPickerBrowser(
    private val fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    private val userHome: Path? = userHomeDirectory(),
) : DirectoryPickerBrowser {
    override suspend fun validate(directory: Path): DirectoryPickerValidationResult {
        val expanded = directory.expandUserHome()
            ?: return DirectoryPickerValidationResult.Failure(
                DirectoryPickerFailure.HomeDirectoryUnavailable,
            )
        return try {
            val resolved = fileSystem.resolve(expanded)
            if (fileSystem.metadataOrNull(resolved)?.isDirectory != true) {
                return DirectoryPickerValidationResult.Failure(
                    DirectoryPickerFailure.NotDirectory(resolved),
                )
            }
            DirectoryPickerValidationResult.Success(resolved)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            DirectoryPickerValidationResult.Failure(
                DirectoryPickerFailure.FileSystem(
                    detail = failure.toString().ifBlank { "Unknown filesystem failure" },
                ),
            )
        }
    }

    override suspend fun load(directory: Path): DirectoryPickerLoadResult {
        val resolved = when (val validation = validate(directory)) {
            is DirectoryPickerValidationResult.Success -> validation.directory
            is DirectoryPickerValidationResult.Failure ->
                return DirectoryPickerLoadResult.Failure(validation.failure)
        }
        return try {
            val children = mutableListOf<Path>()
            for (child in fileSystem.list(resolved)) {
                if (fileSystem.metadataOrNull(child)?.isDirectory == true) children += child
            }
            DirectoryPickerLoadResult.Success(
                DirectoryPickerListing(
                    directory = resolved,
                    children = children.sortedWith(
                        compareBy<Path> { path -> path.name.lowercase() }.thenBy(Path::toString),
                    ),
                ),
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            DirectoryPickerLoadResult.Failure(
                DirectoryPickerFailure.FileSystem(
                    detail = failure.toString().ifBlank { "Unknown filesystem failure" },
                ),
            )
        }
    }

    private fun Path.expandUserHome(): Path? {
        val value = toString()
        val relativePath = when {
            value == HomeShorthand -> ""
            value.startsWith(HomeShorthandPrefix) -> value.removePrefix(HomeShorthandPrefix)
            else -> return this
        }
        val home = userHome ?: return null
        return if (relativePath.isEmpty()) home else Path(home, relativePath)
    }

    private companion object {
        const val HomeShorthand: String = "~"
        val HomeShorthandPrefix: String = "$HomeShorthand$SystemPathSeparator"
    }
}
