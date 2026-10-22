package io.github.stream29.kodex.cli.patch

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolExecutionResult
import io.github.stream29.kodex.utils.applypatch.AddFileHunk
import io.github.stream29.kodex.utils.applypatch.DeleteFileHunk
import io.github.stream29.kodex.utils.applypatch.Patch
import io.github.stream29.kodex.utils.applypatch.UpdateFileChunk
import io.github.stream29.kodex.utils.applypatch.UpdateFileHunk

public actual fun Patch.toPendingPatchPresentation(): PatchPresentation =
    toPatchPresentation(
        action = PatchPresentationAction.Editing,
        target = hunks.map { hunk -> hunk.path }.toPatchPresentationTarget(),
        status = PatchPresentationStatus.Running,
        bodyLines = toInputPresentationLines(),
        emptyMessage = "No patch hunks",
    )

public actual fun StablePatchToolEvent.toStablePatchPresentation(): PatchPresentation =
    when (val result = result) {
        is StablePatchToolExecutionResult.Success ->
            diff.toPatchPresentation(
                action = PatchPresentationAction.Edit,
                target = diff.hunks
                    .map { hunk -> hunk.path }
                    .toPatchPresentationTarget(),
                status = PatchPresentationStatus.Completed,
                bodyLines = diff.toInputPresentationLines(),
                emptyMessage = "No patch hunks",
            )

        is StablePatchToolExecutionResult.Failure ->
            diff.toPatchPresentation(
                action = PatchPresentationAction.FailedToEdit,
                target = diff.hunks
                    .map { hunk -> hunk.path }
                    .toPatchPresentationTarget(),
                status = PatchPresentationStatus.Failed,
                bodyLines = diff.toInputPresentationLines(),
                emptyMessage = "No patch hunks",
                failure = result.reason,
            )
    }

private fun Patch.toPatchPresentation(
    action: PatchPresentationAction,
    target: PatchPresentationTarget,
    status: PatchPresentationStatus,
    bodyLines: List<PatchPresentationLine>,
    emptyMessage: String,
    failure: String? = null,
): PatchPresentation =
    PatchPresentation(
        action = action,
        target = target,
        status = status,
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

private fun List<String>.toPatchPresentationTarget(): PatchPresentationTarget {
    val distinctPaths = distinct()
    if (distinctPaths.size != 1) {
        return PatchPresentationTarget.FileCount(distinctPaths.size)
    }
    val path = distinctPaths.single()
    val filename = path.substringAfterLast('/').substringAfterLast('\\')
        .takeIf(String::isNotBlank)
        ?: path.takeIf(String::isNotBlank)
        ?: "file"
    return PatchPresentationTarget.SingleFile(filename)
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
