package io.github.stream29.codex.lite.cli.pathpicker

import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path

/** One resolved directory and its directly selectable child directories. */
public data class DirectoryPickerListing(
    public val directory: Path,
    public val children: List<Path>,
)

/** Reads directory choices for [DirectoryPickerPopup] without coupling them to the terminal UI. */
public class DirectoryPickerBrowser(
    private val fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
) {
    /** Resolves [directory] and returns its direct child directories in deterministic order. */
    public suspend fun load(directory: Path): DirectoryPickerListing {
        val resolved = fileSystem.resolve(directory)
        require(fileSystem.metadataOrNull(resolved)?.isDirectory == true) {
            "Not a directory: $resolved"
        }

        val children = mutableListOf<Path>()
        for (candidate in fileSystem.list(resolved)) {
            val child = fileSystem.resolve(candidate)
            if (fileSystem.metadataOrNull(child)?.isDirectory == true) children += child
        }
        return DirectoryPickerListing(
            directory = resolved,
            children = children.sortedWith(
                compareBy<Path> { path -> path.name.lowercase() }.thenBy(Path::toString),
            ),
        )
    }
}
