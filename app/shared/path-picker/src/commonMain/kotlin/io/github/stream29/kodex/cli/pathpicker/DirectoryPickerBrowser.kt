package io.github.stream29.kodex.cli.pathpicker

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.osenvironment.userHomeDirectory
import kotlinx.io.files.Path
import kotlinx.io.files.SystemPathSeparator

/** One resolved directory and its directly selectable child directories. */
public data class DirectoryPickerListing(
    public val directory: Path,
    public val children: List<Path>,
)

/** Reads directory choices for [DirectoryPickerPopup] without coupling them to the terminal UI. */
public class DirectoryPickerBrowser(
    private val fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    private val userHome: Path? = userHomeDirectory(),
) {
    /** Resolves [directory] and returns its direct child directories in deterministic order. */
    public suspend fun load(directory: Path): DirectoryPickerListing {
        val resolved = fileSystem.resolve(directory.expandUserHome())
        require(fileSystem.metadataOrNull(resolved)?.isDirectory == true) {
            "Not a directory: $resolved"
        }

        val children = mutableListOf<Path>()
        for (child in fileSystem.list(resolved)) {
            if (fileSystem.metadataOrNull(child)?.isDirectory == true) children += child
        }
        return DirectoryPickerListing(
            directory = resolved,
            children = children.sortedWith(
                compareBy<Path> { path -> path.name.lowercase() }.thenBy(Path::toString),
            ),
        )
    }

    private fun Path.expandUserHome(): Path {
        val value = toString()
        val relativePath = when {
            value == HomeShorthand -> ""
            value.startsWith(HomeShorthandPrefix) -> value.removePrefix(HomeShorthandPrefix)
            else -> return this
        }
        val home = requireNotNull(userHome) {
            "Cannot resolve $HomeShorthand because the user home directory was not found."
        }
        return if (relativePath.isEmpty()) home else Path(home, relativePath)
    }

    private companion object {
        const val HomeShorthand: String = "~"
        val HomeShorthandPrefix: String = "$HomeShorthand$SystemPathSeparator"
    }
}
