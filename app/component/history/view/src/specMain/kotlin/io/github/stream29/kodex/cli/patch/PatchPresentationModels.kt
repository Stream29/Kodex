package io.github.stream29.kodex.cli.patch

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
