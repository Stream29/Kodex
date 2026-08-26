package io.github.stream29.kodex.utils.applypatch

private const val BeginPatchMarker = "*** Begin Patch"
private const val EndPatchMarker = "*** End Patch"
private const val AddFileMarker = "*** Add File: "
private const val DeleteFileMarker = "*** Delete File: "
private const val UpdateFileMarker = "*** Update File: "
private const val MoveToMarker = "*** Move to: "
private const val EndOfFileMarker = "*** End of File"
private const val EmptyChangeContextMarker = "@@"
private const val ChangeContextMarker = "@@ "
private const val EnvironmentIdMarker = "*** Environment ID:"

public fun String.parsePatch(): Patch {
    val patch = this
    val lines = patch.trim().lines()
    val patchLines = checkPatchBoundariesLenient(lines)
    val normalizedPatch = patchLines.joinToString("\n")
    val parser = PatchParser()
    val hunks = parser.parse(patchLines)
    return Patch(
        patch = normalizedPatch,
        hunks = hunks,
        environmentId = parser.environmentId,
    )
}

private class PatchParser {
    private var mode: PatchParserMode = PatchParserMode.NotStarted
    private var lineNumber: Int = 0
    private val hunkBuilders = mutableListOf<HunkBuilder>()

    var environmentId: String? = null
        private set

    fun parse(lines: List<String>): List<Hunk> {
        lines.forEach { line ->
            lineNumber++
            processLine(line)
        }
        if (mode != PatchParserMode.EndedPatch) {
            throw ApplyPatchException("The last line of the patch must be '$EndPatchMarker'")
        }
        return hunkBuilders.map { builder -> builder.build() }
    }

    private fun processLine(line: String) {
        val trimmed = line.trim()
        when (mode) {
            PatchParserMode.NotStarted -> {
                if (trimmed == BeginPatchMarker) {
                    mode = PatchParserMode.StartedPatch
                    return
                }
                throw ApplyPatchException("The first line of the patch must be '$BeginPatchMarker'")
            }
            PatchParserMode.StartedPatch -> {
                if (handleHunkHeadersAndEndPatch(trimmed)) {
                    return
                }
                throw invalid("'$trimmed' is not a valid hunk header")
            }
            PatchParserMode.AddFile -> processAddFileLine(line, trimmed)
            PatchParserMode.DeleteFile -> {
                if (handleHunkHeadersAndEndPatch(trimmed)) {
                    return
                }
                throw invalid("'$trimmed' is not a valid hunk header")
            }
            PatchParserMode.UpdateFile -> processUpdateFileLine(line)
            PatchParserMode.EndedPatch -> {
                if (trimmed.isNotEmpty()) {
                    throw ApplyPatchException("The last line of the patch must be '$EndPatchMarker'")
                }
            }
        }
    }

    private fun processAddFileLine(line: String, trimmed: String) {
        if (handleHunkHeadersAndEndPatch(trimmed)) {
            return
        }
        val lineToAdd = line.removePrefixOrNull("+")
            ?: throw invalid("'$trimmed' is not a valid hunk header")
        val hunk = hunkBuilders.last() as? AddFileHunkBuilder
            ?: throw invalid("add file line outside add hunk")
        hunk.contents.append(lineToAdd).append('\n')
    }

    private fun processUpdateFileLine(line: String) {
        val updateLine = line.trimEnd()
        if (handleHunkHeadersAndEndPatch(updateLine)) {
            return
        }

        val hunk = hunkBuilders.last() as? UpdateFileHunkBuilder
            ?: throw invalid("update file line outside update hunk")
        val chunks = hunk.chunks

        if (chunks.lastOrNull()?.isEndOfFile == true) {
            if (updateLine.isEmpty()) {
                return
            }
            if (updateLine != EmptyChangeContextMarker && !updateLine.startsWith(ChangeContextMarker)) {
                throw invalid("Expected update hunk to start with a @@ context marker, got: '$line'")
            }
        }

        if (chunks.isEmpty() && hunk.movePath == null) {
            updateLine.removePrefixOrNull(MoveToMarker)?.let { moveToPath ->
                hunk.movePath = moveToPath
                return
            }
        }

        if (
            (updateLine == EmptyChangeContextMarker || updateLine.startsWith(ChangeContextMarker)) &&
            chunks.lastOrNull()?.isEmpty() == true
        ) {
            throw invalid("Unexpected line found in update hunk: '$line'")
        }

        when {
            updateLine == EmptyChangeContextMarker -> {
                chunks += UpdateFileChunkBuilder(
                    changeContext = null,
                )
            }
            updateLine.startsWith(ChangeContextMarker) -> {
                chunks += UpdateFileChunkBuilder(
                    changeContext = updateLine.removePrefix(ChangeContextMarker),
                )
            }
            updateLine == EndOfFileMarker -> {
                if (chunks.lastOrNull()?.isEmpty() != false) {
                    throw invalid("Update hunk does not contain any lines")
                }
                chunks.last().isEndOfFile = true
            }
            line.isEmpty() -> {
                chunks.ensureCurrentChunk()
                chunks.last().oldLines += ""
                chunks.last().newLines += ""
            }
            line.startsWith(" ") -> {
                val content = line.drop(1)
                chunks.ensureCurrentChunk()
                chunks.last().oldLines += content
                chunks.last().newLines += content
            }
            line.startsWith("+") -> {
                chunks.ensureCurrentChunk()
                chunks.last().newLines += line.drop(1)
            }
            line.startsWith("-") -> {
                chunks.ensureCurrentChunk()
                chunks.last().oldLines += line.drop(1)
            }
            chunks.lastOrNull()?.isEmpty() == false -> {
                throw invalid("Expected update hunk to start with a @@ context marker, got: '$line'")
            }
            else -> {
                throw invalid(
                    "Unexpected line found in update hunk: '$line'. Every line should start with ' ', '+', or '-'",
                )
            }
        }
    }

    private fun handleHunkHeadersAndEndPatch(trimmed: String): Boolean {
        if (mode == PatchParserMode.StartedPatch) {
            trimmed.removePrefixOrNull(EnvironmentIdMarker)?.let { id ->
                if (environmentId != null) {
                    throw ApplyPatchException("apply_patch environment_id cannot be specified more than once")
                }
                val value = id.trim()
                if (value.isEmpty()) {
                    throw ApplyPatchException("apply_patch environment_id cannot be empty")
                }
                environmentId = value
                return true
            }
        }

        if (trimmed == EndPatchMarker) {
            ensureUpdateHunkIsNotEmpty(trimmed)
            mode = PatchParserMode.EndedPatch
            return true
        }

        trimmed.removePrefixOrNull(AddFileMarker)?.let { path ->
            ensureUpdateHunkIsNotEmpty(trimmed)
            hunkBuilders += AddFileHunkBuilder(path)
            mode = PatchParserMode.AddFile
            return true
        }

        trimmed.removePrefixOrNull(DeleteFileMarker)?.let { path ->
            ensureUpdateHunkIsNotEmpty(trimmed)
            hunkBuilders += DeleteFileHunkBuilder(path)
            mode = PatchParserMode.DeleteFile
            return true
        }

        trimmed.removePrefixOrNull(UpdateFileMarker)?.let { path ->
            ensureUpdateHunkIsNotEmpty(trimmed)
            hunkBuilders += UpdateFileHunkBuilder(path)
            mode = PatchParserMode.UpdateFile
            return true
        }

        return false
    }

    private fun ensureUpdateHunkIsNotEmpty(line: String) {
        val hunk = hunkBuilders.lastOrNull() as? UpdateFileHunkBuilder ?: return
        if (hunk.chunks.isEmpty()) {
            throw invalid("Update file hunk for path '${hunk.path}' is empty")
        }
        if (hunk.chunks.last().isEmpty()) {
            if (line == EndPatchMarker) {
                throw invalid("Update hunk does not contain any lines")
            }
            throw invalid("Unexpected line found in update hunk: '$line'")
        }
    }

    private fun invalid(message: String): ApplyPatchException =
        ApplyPatchException("Invalid patch hunk at line $lineNumber, $message")
}

private sealed interface HunkBuilder {
    val path: String

    fun build(): Hunk
}

private class AddFileHunkBuilder(
    override val path: String,
) : HunkBuilder {
    val contents: StringBuilder = StringBuilder()

    override fun build(): Hunk = AddFileHunk(
        path = path,
        contents = contents.toString(),
    )
}

private class DeleteFileHunkBuilder(
    override val path: String,
) : HunkBuilder {
    override fun build(): Hunk = DeleteFileHunk(path)
}

private class UpdateFileHunkBuilder(
    override val path: String,
) : HunkBuilder {
    var movePath: String? = null
    val chunks: MutableList<UpdateFileChunkBuilder> = mutableListOf()

    override fun build(): Hunk = UpdateFileHunk(
        path = path,
        movePath = movePath,
        chunks = chunks.map { chunk -> chunk.build() },
    )
}

private class UpdateFileChunkBuilder(
    val changeContext: String?,
) {
    val oldLines: MutableList<String> = mutableListOf()
    val newLines: MutableList<String> = mutableListOf()
    var isEndOfFile: Boolean = false

    fun build(): UpdateFileChunk = UpdateFileChunk(
        changeContext = changeContext,
        oldLines = oldLines.toList(),
        newLines = newLines.toList(),
        isEndOfFile = isEndOfFile,
    )

    fun isEmpty(): Boolean = oldLines.isEmpty() && newLines.isEmpty()
}

private enum class PatchParserMode {
    NotStarted,
    StartedPatch,
    AddFile,
    DeleteFile,
    UpdateFile,
    EndedPatch,
}

private fun checkPatchBoundariesLenient(lines: List<String>): List<String> {
    val strict = checkPatchBoundariesStrictOrNull(lines)
    if (strict != null) {
        return strict
    }
    if (
        lines.size >= 4 &&
        lines.first() in setOf("<<EOF", "<<'EOF'", "<<\"EOF\"") &&
        lines.last().endsWith("EOF")
    ) {
        return checkPatchBoundariesStrictOrNull(lines.drop(1).dropLast(1))
            ?: throw patchBoundaryError(lines.drop(1).dropLast(1))
    }
    throw patchBoundaryError(lines)
}

private fun checkPatchBoundariesStrictOrNull(lines: List<String>): List<String>? {
    val first = lines.firstOrNull()?.trim()
    val last = lines.lastOrNull()?.trim()
    return if (first == BeginPatchMarker && last == EndPatchMarker) lines else null
}

private fun patchBoundaryError(lines: List<String>): ApplyPatchException {
    val first = lines.firstOrNull()?.trim()
    return if (first != BeginPatchMarker) {
        ApplyPatchException("The first line of the patch must be '$BeginPatchMarker'")
    } else {
        ApplyPatchException("The last line of the patch must be '$EndPatchMarker'")
    }
}

private fun String.removePrefixOrNull(prefix: String): String? =
    if (startsWith(prefix)) removePrefix(prefix) else null

private fun MutableList<UpdateFileChunkBuilder>.ensureCurrentChunk() {
    if (isEmpty()) {
        this += UpdateFileChunkBuilder(
            changeContext = null,
        )
    }
}
