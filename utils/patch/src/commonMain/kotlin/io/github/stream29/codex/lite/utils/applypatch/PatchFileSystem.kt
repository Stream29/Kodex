package io.github.stream29.codex.lite.utils.applypatch

import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path

public suspend fun Patch.applyToFileSystem(
    root: Path = Path("."),
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): PatchApplyResult =
    applyHunksToFileSystem(hunks, root, fileSystem)

private suspend fun applyHunksToFileSystem(
    hunks: List<Hunk>,
    root: Path,
    fileSystem: CoroutineFileSystem,
): PatchApplyResult {
    if (hunks.isEmpty()) {
        throw ApplyPatchException("No files were modified.")
    }

    val added = mutableListOf<String>()
    val modified = mutableListOf<String>()
    val deleted = mutableListOf<String>()
    val changes = mutableListOf<PatchChange>()

    fun resolve(path: String): Path {
        val candidate = Path(path)
        return if (candidate.isAbsolute) candidate else Path(root, path)
    }

    suspend fun ensureExistingRegularFile(path: String) {
        val resolved = resolve(path)
        val metadata = fileSystem.metadataOrNull(resolved)
            ?: throw ApplyPatchException("File does not exist: $path")
        if (!metadata.isRegularFile) {
            throw ApplyPatchException("Path is not a regular file: $path")
        }
    }

    suspend fun readExistingFile(path: String): String {
        val resolved = resolve(path)
        ensureExistingRegularFile(path)
        return fileSystem.readString(resolved)
    }

    suspend fun readOptionalFile(path: String): String? {
        val resolved = resolve(path)
        return if (fileSystem.metadataOrNull(resolved)?.isRegularFile == true) {
            readExistingFile(path)
        } else {
            null
        }
    }

    suspend fun writeFile(path: String, content: String) {
        val resolved = resolve(path)
        resolved.parent?.let { fileSystem.createDirectories(it) }
        fileSystem.writeString(resolved, content)
    }

    suspend fun deleteFile(path: String) {
        val resolved = resolve(path)
        ensureExistingRegularFile(path)
        fileSystem.delete(resolved)
    }

    hunks.forEach { hunk ->
        when (hunk) {
            is AddFileHunk -> {
                val overwrittenContent = readOptionalFile(hunk.path)
                writeFile(hunk.path, hunk.contents)
                changes += PatchChange(
                    path = hunk.path,
                    change = PatchFileChange.Add(
                        content = hunk.contents,
                        overwrittenContent = overwrittenContent,
                    ),
                )
                added += hunk.path
            }
            is DeleteFileHunk -> {
                val content = readExistingFile(hunk.path)
                deleteFile(hunk.path)
                changes += PatchChange(
                    path = hunk.path,
                    change = PatchFileChange.Delete(content),
                )
                deleted += hunk.path
            }
            is UpdateFileHunk -> {
                val originalContent = readExistingFile(hunk.path)
                val newContent = deriveNewContent(hunk.path, originalContent, hunk.chunks)
                val movePath = hunk.movePath
                if (movePath == null) {
                    writeFile(hunk.path, newContent)
                    changes += PatchChange(
                        path = hunk.path,
                        change = PatchFileChange.Update(
                            movePath = null,
                            oldContent = originalContent,
                            overwrittenMoveContent = null,
                            newContent = newContent,
                        ),
                    )
                    modified += hunk.path
                } else {
                    val overwrittenMoveContent = readOptionalFile(movePath)
                    writeFile(movePath, newContent)
                    deleteFile(hunk.path)
                    changes += PatchChange(
                        path = hunk.path,
                        change = PatchFileChange.Update(
                            movePath = movePath,
                            oldContent = originalContent,
                            overwrittenMoveContent = overwrittenMoveContent,
                            newContent = newContent,
                        ),
                    )
                    modified += movePath
                }
            }
        }
    }

    return PatchApplyResult(
        affectedPaths = PatchAffectedPaths(
            added = added,
            modified = modified,
            deleted = deleted,
        ),
        delta = PatchDelta(
            changes = changes,
            exact = true,
        ),
    )
}

private fun deriveNewContent(path: String, originalContent: String, chunks: List<UpdateFileChunk>): String {
    if (chunks.isEmpty()) {
        return originalContent
    }

    val originalLines = originalContent.toFileLines()
    val replacements = computeReplacements(path, originalLines, chunks)
    val newLines = applyReplacements(originalLines, replacements).toMutableList()
    if (newLines.lastOrNull() != "") {
        newLines += ""
    }
    return newLines.joinToString("\n")
}

private fun computeReplacements(
    path: String,
    originalLines: List<String>,
    chunks: List<UpdateFileChunk>,
): List<Replacement> {
    val replacements = mutableListOf<Replacement>()
    var lineIndex = 0

    chunks.forEach { chunk ->
        chunk.changeContext?.let { context ->
            val contextIndex = findLineSequence(
                lines = originalLines,
                targetLines = listOf(context),
                startIndex = lineIndex,
                anchorAtEnd = false,
            )
                ?: throw ApplyPatchException("Failed to find context '$context' in $path")
            lineIndex = contextIndex + 1
        }

        if (chunk.oldLines.isEmpty()) {
            replacements += Replacement(
                startIndex = originalLines.size,
                oldLength = 0,
                newLines = chunk.newLines,
            )
            return@forEach
        }

        var oldLines = chunk.oldLines
        var newLines = chunk.newLines
        var found = findLineSequence(
            lines = originalLines,
            targetLines = oldLines,
            startIndex = lineIndex,
            anchorAtEnd = chunk.isEndOfFile,
        )

        if (found == null && oldLines.lastOrNull() == "") {
            oldLines = oldLines.dropLast(1)
            if (newLines.lastOrNull() == "") {
                newLines = newLines.dropLast(1)
            }
            found = findLineSequence(
                lines = originalLines,
                targetLines = oldLines,
                startIndex = lineIndex,
                anchorAtEnd = chunk.isEndOfFile,
            )
        }

        val startIndex = found ?: throw ApplyPatchException(
            "Failed to find expected lines in $path:\n${chunk.oldLines.joinToString("\n")}",
        )
        replacements += Replacement(
            startIndex = startIndex,
            oldLength = oldLines.size,
            newLines = newLines,
        )
        lineIndex = startIndex + oldLines.size
    }

    return replacements.sortedBy { it.startIndex }
}

private data class Replacement(
    val startIndex: Int,
    val oldLength: Int,
    val newLines: List<String>,
)

private fun String.toFileLines(): List<String> {
    val lines = split('\n').toMutableList()
    if (lines.lastOrNull() == "") {
        lines.removeAt(lines.lastIndex)
    }
    return lines
}

private fun applyReplacements(
    originalLines: List<String>,
    replacements: List<Replacement>,
): List<String> {
    val lines = originalLines.toMutableList()
    replacements.asReversed().forEach { replacement ->
        repeat(replacement.oldLength) {
            if (replacement.startIndex < lines.size) {
                lines.removeAt(replacement.startIndex)
            }
        }
        replacement.newLines.forEachIndexed { offset, line ->
            lines.add(replacement.startIndex + offset, line)
        }
    }
    return lines
}
