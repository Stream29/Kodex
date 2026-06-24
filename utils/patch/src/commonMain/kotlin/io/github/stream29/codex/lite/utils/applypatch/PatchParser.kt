package io.github.stream29.codex.lite.utils.applypatch

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
    val parser = IncrementalPatchParser()
    parser.pushDelta(normalizedPatch)
    val hunks = parser.finish()
    return Patch(
        patch = normalizedPatch,
        hunks = hunks,
        environmentId = parser.environmentId,
    )
}

/**
 * Parses already-normalized patch text chunks in order.
 */
public fun Sequence<String>.parsePatch(): Patch {
    val parser = IncrementalPatchParser()
    val normalizedPatch = StringBuilder()
    forEach { delta ->
        normalizedPatch.append(delta)
        parser.pushDelta(delta)
    }
    val hunks = parser.finish()
    return Patch(
        patch = normalizedPatch.toString(),
        hunks = hunks,
        environmentId = parser.environmentId,
    )
}

internal class IncrementalPatchParser {
    private var lineBuffer: String = ""
    private var mode: StreamingParserMode = StreamingParserMode.NotStarted
    private var lineNumber: Int = 0
    private val mutableHunks = mutableListOf<Hunk>()

    internal var environmentId: String? = null
        private set

    internal fun pushDelta(delta: String): List<Hunk> {
        delta.forEach { ch ->
            if (ch == '\n') {
                val line = lineBuffer.removeSuffix("\r")
                lineBuffer = ""
                lineNumber++
                processLine(line)
            } else {
                lineBuffer += ch
            }
        }
        return mutableHunks.toList()
    }

    internal fun finish(): List<Hunk> {
        if (lineBuffer.isNotEmpty()) {
            val line = lineBuffer
            lineBuffer = ""
            lineNumber++
            if (line.trim() == EndPatchMarker) {
                ensureUpdateHunkIsNotEmpty(line.trim())
                mode = StreamingParserMode.EndedPatch
            } else {
                processLine(line)
            }
        }
        if (mode != StreamingParserMode.EndedPatch) {
            throw ApplyPatchException("The last line of the patch must be '$EndPatchMarker'")
        }
        return mutableHunks.toList()
    }

    private fun processLine(line: String) {
        val trimmed = line.trim()
        when (mode) {
            StreamingParserMode.NotStarted -> {
                if (trimmed == BeginPatchMarker) {
                    mode = StreamingParserMode.StartedPatch
                    return
                }
                throw ApplyPatchException("The first line of the patch must be '$BeginPatchMarker'")
            }
            StreamingParserMode.StartedPatch -> {
                if (handleHunkHeadersAndEndPatch(trimmed)) {
                    return
                }
                throw invalid("'$trimmed' is not a valid hunk header")
            }
            StreamingParserMode.AddFile -> processAddFileLine(line, trimmed)
            StreamingParserMode.DeleteFile -> {
                if (handleHunkHeadersAndEndPatch(trimmed)) {
                    return
                }
                throw invalid("'$trimmed' is not a valid hunk header")
            }
            StreamingParserMode.UpdateFile -> processUpdateFileLine(line)
            StreamingParserMode.EndedPatch -> {
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
        val hunk = mutableHunks.last() as? AddFileHunk
            ?: throw invalid("add file line outside add hunk")
        mutableHunks[mutableHunks.lastIndex] = hunk.copy(contents = hunk.contents + lineToAdd + "\n")
    }

    private fun processUpdateFileLine(line: String) {
        val updateLine = line.trimEnd()
        if (handleHunkHeadersAndEndPatch(updateLine)) {
            return
        }

        val hunk = mutableHunks.last() as? UpdateFileHunk
            ?: throw invalid("update file line outside update hunk")
        val chunks = hunk.chunks.toMutableList()

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
                mutableHunks[mutableHunks.lastIndex] = hunk.copy(movePath = moveToPath)
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
                chunks += UpdateFileChunk(
                    changeContext = null,
                    oldLines = emptyList(),
                    newLines = emptyList(),
                    isEndOfFile = false,
                )
            }
            updateLine.startsWith(ChangeContextMarker) -> {
                chunks += UpdateFileChunk(
                    changeContext = updateLine.removePrefix(ChangeContextMarker),
                    oldLines = emptyList(),
                    newLines = emptyList(),
                    isEndOfFile = false,
                )
            }
            updateLine == EndOfFileMarker -> {
                if (chunks.lastOrNull()?.isEmpty() != false) {
                    throw invalid("Update hunk does not contain any lines")
                }
                chunks[chunks.lastIndex] = chunks.last().copy(isEndOfFile = true)
            }
            line.isEmpty() -> {
                chunks.ensureCurrentChunk()
                chunks[chunks.lastIndex] = chunks.last().copy(
                    oldLines = chunks.last().oldLines + "",
                    newLines = chunks.last().newLines + "",
                )
            }
            line.startsWith(" ") -> {
                val content = line.drop(1)
                chunks.ensureCurrentChunk()
                chunks[chunks.lastIndex] = chunks.last().copy(
                    oldLines = chunks.last().oldLines + content,
                    newLines = chunks.last().newLines + content,
                )
            }
            line.startsWith("+") -> {
                chunks.ensureCurrentChunk()
                chunks[chunks.lastIndex] = chunks.last().copy(
                    newLines = chunks.last().newLines + line.drop(1),
                )
            }
            line.startsWith("-") -> {
                chunks.ensureCurrentChunk()
                chunks[chunks.lastIndex] = chunks.last().copy(
                    oldLines = chunks.last().oldLines + line.drop(1),
                )
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

        mutableHunks[mutableHunks.lastIndex] = hunk.copy(chunks = chunks)
    }

    private fun handleHunkHeadersAndEndPatch(trimmed: String): Boolean {
        if (mode == StreamingParserMode.StartedPatch) {
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
            mode = StreamingParserMode.EndedPatch
            return true
        }

        trimmed.removePrefixOrNull(AddFileMarker)?.let { path ->
            ensureUpdateHunkIsNotEmpty(trimmed)
            mutableHunks += AddFileHunk(path = path, contents = "")
            mode = StreamingParserMode.AddFile
            return true
        }

        trimmed.removePrefixOrNull(DeleteFileMarker)?.let { path ->
            ensureUpdateHunkIsNotEmpty(trimmed)
            mutableHunks += DeleteFileHunk(path)
            mode = StreamingParserMode.DeleteFile
            return true
        }

        trimmed.removePrefixOrNull(UpdateFileMarker)?.let { path ->
            ensureUpdateHunkIsNotEmpty(trimmed)
            mutableHunks += UpdateFileHunk(path = path, movePath = null, chunks = emptyList())
            mode = StreamingParserMode.UpdateFile
            return true
        }

        return false
    }

    private fun ensureUpdateHunkIsNotEmpty(line: String) {
        val hunk = mutableHunks.lastOrNull() as? UpdateFileHunk ?: return
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

private enum class StreamingParserMode {
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

private fun UpdateFileChunk.isEmpty(): Boolean =
    oldLines.isEmpty() && newLines.isEmpty()

private fun MutableList<UpdateFileChunk>.ensureCurrentChunk() {
    if (isEmpty()) {
        this += UpdateFileChunk(
            changeContext = null,
            oldLines = emptyList(),
            newLines = emptyList(),
            isEndOfFile = false,
        )
    }
}
