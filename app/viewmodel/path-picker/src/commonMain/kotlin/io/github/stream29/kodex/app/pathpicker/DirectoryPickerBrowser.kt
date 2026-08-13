package io.github.stream29.kodex.app.pathpicker

import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerFailure
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.osenvironment.userHomeDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemPathSeparator

internal data class DirectoryPickerListing(
    val directory: Path,
    val children: List<Path>,
)

internal sealed interface DirectoryPickerBrowserResult {
    data class Success(
        val listing: DirectoryPickerListing,
    ) : DirectoryPickerBrowserResult

    data class Failure(
        val failure: DirectoryPickerFailure,
    ) : DirectoryPickerBrowserResult
}

/** Resolves and lists directory choices without depending on a frontend renderer. */
internal class DirectoryPickerBrowser(
    private val fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    private val userHome: Path? = userHomeDirectory(),
) {
    suspend fun load(directory: Path): DirectoryPickerBrowserResult {
        val expanded = directory.expandUserHome()
            ?: return DirectoryPickerBrowserResult.Failure(
                DirectoryPickerFailure.HomeDirectoryUnavailable,
            )
        return try {
            val resolved = fileSystem.resolve(expanded)
            if (fileSystem.metadataOrNull(resolved)?.isDirectory != true) {
                return DirectoryPickerBrowserResult.Failure(
                    DirectoryPickerFailure.NotDirectory(resolved),
                )
            }

            val children = mutableListOf<Path>()
            for (child in fileSystem.list(resolved)) {
                if (fileSystem.metadataOrNull(child)?.isDirectory == true) children += child
            }
            DirectoryPickerBrowserResult.Success(
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
            DirectoryPickerBrowserResult.Failure(
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
