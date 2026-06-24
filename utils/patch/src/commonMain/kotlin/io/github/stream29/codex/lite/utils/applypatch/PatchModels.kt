package io.github.stream29.codex.lite.utils.applypatch

/**
 * Parsed apply_patch input, preserving both the normalized patch text and its
 * semantic hunks.
 *
 * @property workdir Nullable because plain patch text does not carry an
 * explicit working directory; `null` means the caller supplies the apply root.
 * @property environmentId Nullable because most patches do not target a named
 * execution environment; `null` means no environment constraint was provided.
 */
public data class Patch(
    public val patch: String,
    public val hunks: List<Hunk>,
    public val workdir: String? = null,
    public val environmentId: String? = null,
)

public sealed interface Hunk {
    public val path: String
}

public data class AddFileHunk(
    override val path: String,
    public val contents: String,
) : Hunk

public data class DeleteFileHunk(
    override val path: String,
) : Hunk

/**
 * @property movePath Nullable because most update hunks modify the file in
 * place; `null` means the updated content remains at [path].
 */
public data class UpdateFileHunk(
    override val path: String,
    public val movePath: String? = null,
    public val chunks: List<UpdateFileChunk>,
) : Hunk

/**
 * @property changeContext Nullable because chunks may rely only on old-line
 * matching; `null` means no extra context anchor was provided.
 */
public data class UpdateFileChunk(
    public val changeContext: String? = null,
    public val oldLines: List<String>,
    public val newLines: List<String>,
    public val isEndOfFile: Boolean = false,
)

public data class PatchAffectedPaths(
    public val added: List<String>,
    public val modified: List<String>,
    public val deleted: List<String>,
)

public data class PatchApplyResult(
    public val affectedPaths: PatchAffectedPaths,
    public val delta: PatchDelta,
)

public data class PatchDelta(
    public val changes: List<PatchChange>,
    public val exact: Boolean,
)

public data class PatchChange(
    public val path: String,
    public val change: PatchFileChange,
)

public sealed interface PatchFileChange {
    /**
     * @property overwrittenContent Nullable because an add normally creates a
     * new file; `null` means no previous content was overwritten.
     */
    public data class Add(
        public val content: String,
        public val overwrittenContent: String?,
    ) : PatchFileChange

    public data class Delete(
        public val content: String,
    ) : PatchFileChange

    /**
     * @property movePath Nullable because most updates are in-place; `null`
     * means the original path remains the output path.
     * @property overwrittenMoveContent Nullable because moves normally write to
     * a new destination; `null` means no destination content was overwritten, or
     * the update was not a move.
     */
    public data class Update(
        public val movePath: String?,
        public val oldContent: String,
        public val overwrittenMoveContent: String?,
        public val newContent: String,
    ) : PatchFileChange
}

public class ApplyPatchException(
    message: String,
) : IllegalArgumentException(message)
