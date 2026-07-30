package io.github.stream29.codex.lite.cli.patch

import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StablePatchToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StablePatchToolExecutionResult
import io.github.stream29.codex.lite.utils.applypatch.AddFileHunk
import io.github.stream29.codex.lite.utils.applypatch.DeleteFileHunk
import io.github.stream29.codex.lite.utils.applypatch.Patch
import io.github.stream29.codex.lite.utils.applypatch.PatchApplyResult
import io.github.stream29.codex.lite.utils.applypatch.PatchChange
import io.github.stream29.codex.lite.utils.applypatch.PatchFileChange
import io.github.stream29.codex.lite.utils.applypatch.UpdateFileChunk
import io.github.stream29.codex.lite.utils.applypatch.UpdateFileHunk

internal data class PatchPresentation(
    val header: String,
    val rawToolName: String,
    val lines: List<PatchPresentationLine>,
)

internal data class PatchPresentationLine(
    val text: String,
    val kind: PatchPresentationLineKind,
)

internal enum class PatchPresentationLineKind {
    Metadata,
    File,
    Context,
    Addition,
    Removal,
    Failure,
}

internal fun Patch.toPendingPatchPresentation(): PatchPresentation =
    toPatchPresentation(
        summary = fileEditSummary("Editing"),
        status = "running",
        bodyLines = toInputPresentationLines(),
        emptyMessage = "No patch hunks",
    )

internal fun StablePatchToolEvent.toStablePatchPresentation(): PatchPresentation =
    when (val result = result) {
        is StablePatchToolExecutionResult.Success ->
            diff.toPatchPresentation(
                summary = result.applyResult.fileEditSummary("Edited"),
                status = "succeeded",
                bodyLines = result.applyResult.toPresentationLines(),
                emptyMessage = "No applied changes",
            )

        is StablePatchToolExecutionResult.Failure ->
            diff.toPatchPresentation(
                summary = diff.fileEditSummary("Editing"),
                status = "failed",
                bodyLines = diff.toInputPresentationLines(),
                emptyMessage = "No patch hunks",
                failure = result.reason,
            )
    }

private fun Patch.toPatchPresentation(
    summary: String,
    status: String,
    bodyLines: List<PatchPresentationLine>,
    emptyMessage: String,
    failure: String? = null,
): PatchPresentation =
    PatchPresentation(
        header = "$summary · $status",
        rawToolName = "apply_patch",
        lines = buildList {
            workdir?.takeIf(String::isNotBlank)?.let { workdir ->
                add(PatchPresentationLine("Working directory: $workdir", PatchPresentationLineKind.Metadata))
            }
            environmentId?.takeIf(String::isNotBlank)?.let { environmentId ->
                add(PatchPresentationLine("Environment: $environmentId", PatchPresentationLineKind.Metadata))
            }
            failure?.takeIf(String::isNotBlank)?.let { failure ->
                add(PatchPresentationLine("Error: $failure", PatchPresentationLineKind.Failure))
            }
            addAll(bodyLines)
            if (bodyLines.isEmpty()) {
                add(PatchPresentationLine(emptyMessage, PatchPresentationLineKind.Metadata))
            }
        },
    )

private fun Patch.fileEditSummary(verb: String): String =
    hunks.map { hunk -> hunk.path }.fileEditSummary(verb)

private fun PatchApplyResult.fileEditSummary(verb: String): String =
    delta.changes.map { change -> change.path }.fileEditSummary(verb)

private fun List<String>.fileEditSummary(verb: String): String {
    val count = distinct().size
    return "$verb $count ${if (count == 1) "file" else "files"}"
}

private fun Patch.toInputPresentationLines(): List<PatchPresentationLine> = buildList {
    hunks.forEach { hunk ->
        when (hunk) {
            is AddFileHunk -> {
                add(PatchPresentationLine("A ${hunk.path}", PatchPresentationLineKind.File))
                hunk.contents.toFileLines().forEach { line ->
                    add(PatchPresentationLine("+ $line", PatchPresentationLineKind.Addition))
                }
            }

            is DeleteFileHunk ->
                add(PatchPresentationLine("D ${hunk.path}", PatchPresentationLineKind.File))

            is UpdateFileHunk -> {
                val fileHeader = hunk.movePath?.let { destination ->
                    "R ${hunk.path} -> $destination"
                } ?: "M ${hunk.path}"
                add(PatchPresentationLine(fileHeader, PatchPresentationLineKind.File))
                hunk.chunks.forEach { chunk ->
                    addAll(chunk.toPresentationLines())
                }
            }
        }
    }
}

private fun PatchApplyResult.toPresentationLines(): List<PatchPresentationLine> = buildList {
    if (!delta.exact) {
        add(
            PatchPresentationLine(
                text = "Applied delta is approximate",
                kind = PatchPresentationLineKind.Metadata,
            ),
        )
    }
    delta.changes.forEach { change ->
        addAppliedChange(change)
    }
}

private fun MutableList<PatchPresentationLine>.addAppliedChange(change: PatchChange) {
    when (val fileChange = change.change) {
        is PatchFileChange.Add -> {
            val overwrittenContent = fileChange.overwrittenContent
            if (overwrittenContent == null) {
                add(PatchPresentationLine("A ${change.path}", PatchPresentationLineKind.File))
                fileChange.content.toFileLines().forEach { line ->
                    add(PatchPresentationLine("+ $line", PatchPresentationLineKind.Addition))
                }
            } else {
                add(
                    PatchPresentationLine(
                        text = "M ${change.path} (replaced by add)",
                        kind = PatchPresentationLineKind.File,
                    ),
                )
                addAppliedContentDiff(
                    oldContent = overwrittenContent,
                    newContent = fileChange.content,
                )
            }
        }

        is PatchFileChange.Delete -> {
            add(PatchPresentationLine("D ${change.path}", PatchPresentationLineKind.File))
            fileChange.content.toFileLines().forEach { line ->
                add(PatchPresentationLine("- $line", PatchPresentationLineKind.Removal))
            }
        }

        is PatchFileChange.Update -> {
            val destination = fileChange.movePath
            val fileHeader = destination?.let { movePath ->
                "R ${change.path} -> $movePath"
            } ?: "M ${change.path}"
            add(PatchPresentationLine(fileHeader, PatchPresentationLineKind.File))
            if (fileChange.overwrittenMoveContent != null) {
                add(
                    PatchPresentationLine(
                        text = "Overwrote existing destination: ${checkNotNull(destination)}",
                        kind = PatchPresentationLineKind.Metadata,
                    ),
                )
            }
            addAppliedContentDiff(
                oldContent = fileChange.oldContent,
                newContent = fileChange.newContent,
            )
        }
    }
}

private fun MutableList<PatchPresentationLine>.addAppliedContentDiff(
    oldContent: String,
    newContent: String,
) {
    val diffLines = diffPatchLines(
        oldLines = oldContent.toFileLines(),
        newLines = newContent.toFileLines(),
    )
    val compactedLines = diffLines.compactPatchContext()
    if (compactedLines.isEmpty()) {
        add(PatchPresentationLine("  No content changes", PatchPresentationLineKind.Context))
    } else {
        add(PatchPresentationLine("@@ applied", PatchPresentationLineKind.Context))
        addAll(compactedLines)
    }
}

private fun UpdateFileChunk.toPresentationLines(): List<PatchPresentationLine> = buildList {
    val chunkHeader = buildString {
        append("@@")
        changeContext?.takeIf(String::isNotBlank)?.let { context ->
            append(" $context")
        }
        if (isEndOfFile) {
            append(" (end of file)")
        }
    }
    add(PatchPresentationLine(chunkHeader, PatchPresentationLineKind.Context))
    addAll(
        diffPatchLines(
            oldLines = oldLines,
            newLines = newLines,
        ),
    )
}

private fun String.toFileLines(): List<String> {
    val lines = split('\n')
    return if (lines.lastOrNull().isNullOrEmpty()) {
        lines.dropLast(1)
    } else {
        lines
    }
}
