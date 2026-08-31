package io.github.stream29.kodex.cli.patch

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StablePatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StablePatchToolExecutionResult
import io.github.stream29.kodex.utils.applypatch.AddFileHunk
import io.github.stream29.kodex.utils.applypatch.DeleteFileHunk
import io.github.stream29.kodex.utils.applypatch.Patch
import io.github.stream29.kodex.utils.applypatch.PatchApplyResult
import io.github.stream29.kodex.utils.applypatch.PatchChange
import io.github.stream29.kodex.utils.applypatch.PatchFileChange
import io.github.stream29.kodex.utils.applypatch.UpdateFileChunk
import io.github.stream29.kodex.utils.applypatch.UpdateFileHunk

public data class PatchPresentation(
    public val action: PatchPresentationAction,
    public val target: PatchPresentationTarget,
    public val status: PatchPresentationStatus,
    public val rawToolName: String,
    public val lines: List<PatchPresentationLine>,
) {
    public val header: String
        get() = "${action.label} ${target.label}"
}

public enum class PatchPresentationAction(
    internal val label: String,
) {
    Editing("Editing"),
    Edit("Edit"),
    FailedToEdit("Failed to edit"),
}

public sealed interface PatchPresentationTarget {
    public val label: String

    public data class SingleFile(
        public val filename: String,
    ) : PatchPresentationTarget {
        init {
            require(filename.isNotBlank()) { "A patch filename must not be blank." }
        }

        override val label: String = filename
    }

    public data class FileCount(
        public val count: Int,
    ) : PatchPresentationTarget {
        init {
            require(count >= 0) { "A patch file count must not be negative." }
        }

        override val label: String = "$count ${if (count == 1) "file" else "files"}"
    }
}

public enum class PatchPresentationStatus {
    Running,
    Completed,
    Failed,
}

public data class PatchPresentationLine(
    public val text: String,
    public val kind: PatchPresentationLineKind,
)

public enum class PatchPresentationLineKind {
    Metadata,
    File,
    Context,
    Addition,
    Removal,
    Failure,
}

public fun Patch.toPendingPatchPresentation(): PatchPresentation =
    toPatchPresentation(
        action = PatchPresentationAction.Editing,
        target = hunks.map { hunk -> hunk.path }.toPatchPresentationTarget(),
        status = PatchPresentationStatus.Running,
        bodyLines = toInputPresentationLines(),
        emptyMessage = "No patch hunks",
    )

public fun StablePatchToolEvent.toStablePatchPresentation(): PatchPresentation =
    when (val result = result) {
        is StablePatchToolExecutionResult.Success ->
            diff.toPatchPresentation(
                action = PatchPresentationAction.Edit,
                target = result.applyResult.delta.changes
                    .map { change -> change.path }
                    .toPatchPresentationTarget(),
                status = PatchPresentationStatus.Completed,
                bodyLines = result.applyResult.toPresentationLines(),
                emptyMessage = "No applied changes",
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
