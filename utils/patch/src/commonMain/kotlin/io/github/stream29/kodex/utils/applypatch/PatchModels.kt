package io.github.stream29.kodex.utils.applypatch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Parsed apply_patch input, preserving both the normalized patch text and its
 * semantic hunks.
 *
 * @property workdir Nullable because plain patch text does not carry an
 * explicit working directory; `null` means the caller supplies the apply root.
 * @property environmentId Nullable because most patches do not target a named
 * execution environment; `null` means no environment constraint was provided.
 */
@Serializable
public data class Patch(
    public val patch: String,
    public val hunks: List<Hunk>,
    public val workdir: String? = null,
    @SerialName("environment_id")
    public val environmentId: String? = null,
)

@Serializable
public sealed interface Hunk {
    public val path: String
}

@Serializable
@SerialName("add_file")
public data class AddFileHunk(
    override val path: String,
    public val contents: String,
) : Hunk

@Serializable
@SerialName("delete_file")
public data class DeleteFileHunk(
    override val path: String,
) : Hunk

/**
 * @property movePath Nullable because most update hunks modify the file in
 * place; `null` means the updated content remains at [path].
 */
@Serializable
@SerialName("update_file")
public data class UpdateFileHunk(
    override val path: String,
    @SerialName("move_path")
    public val movePath: String? = null,
    public val chunks: List<UpdateFileChunk>,
) : Hunk

/**
 * @property changeContext Nullable because chunks may rely only on old-line
 * matching; `null` means no extra context anchor was provided.
 */
@Serializable
public data class UpdateFileChunk(
    @SerialName("change_context")
    public val changeContext: String? = null,
    @SerialName("old_lines")
    public val oldLines: List<String>,
    @SerialName("new_lines")
    public val newLines: List<String>,
    @SerialName("is_end_of_file")
    public val isEndOfFile: Boolean = false,
)

@Serializable
public data class PatchAffectedPaths(
    public val added: List<String>,
    public val modified: List<String>,
    public val deleted: List<String>,
)

@Serializable
public data class PatchApplyResult(
    @SerialName("affected_paths")
    public val affectedPaths: PatchAffectedPaths,
)

public class ApplyPatchException(
    message: String,
) : IllegalArgumentException(message)
