package io.github.stream29.kodex.tool.unifiedexec

import io.github.stream29.kodex.tool.intentname.isValidIntentName
import io.github.stream29.kodex.utils.shellclient.Shell
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Default approximate output-token budget for each returned chunk. */
public const val UnifiedExecDefaultMaxOutputTokens: Long = 10_000L

/**
 * Arguments accepted by the `exec_command` function tool. Commands run in a
 * pseudoterminal; terminal allocation is not a caller-selectable option.
 *
 * @property command Nonblank command text to execute in the selected shell.
 * @property name Required nonblank, single-line intent shown to the user.
 * C0/C1 control characters and Unicode line separators are invalid.
 * @property workdir Nullable because a command normally uses the session
 * working directory; `null` or an empty path uses that directory, while a
 * relative path resolves against it.
 * @property shell Nullable because the host selects its current configured
 * shell when no shell is given; `null` uses that shell at process start.
 * @property yieldTimeMillis Required wait the caller chose for this command
 * before returning available output; the call returns earlier when the
 * command exits, and the host may clamp the value to supported limits.
 * Negative values are invalid.
 * @property maxOutputTokens Approximate token budget for the returned output;
 * negative values are invalid and the byte ceiling still applies.
 */
@Serializable
public data class ExecCommandArguments(
    @SerialName("cmd")
    public val command: String,
    public val name: String,
    public val workdir: String? = null,
    public val shell: Shell? = null,
    @SerialName("yield_time_ms")
    public val yieldTimeMillis: Long,
    @SerialName("max_output_tokens")
    public val maxOutputTokens: Long = UnifiedExecDefaultMaxOutputTokens,
) {
    init {
        require(name.isValidIntentName()) { "`name` must be nonblank, single-line text." }
    }
}

/**
 * Historical arguments recorded by legacy `write_stdin` tool commits.
 *
 * Retained as a serializable DTO for deserializing and rendering existing session history.
 */
@Serializable
public data class WriteStdinArguments(
    @SerialName("session_id")
    public val sessionId: Int,
    public val chars: String = "",
    @SerialName("yield_time_ms")
    public val yieldTimeMillis: Long,
    @SerialName("max_output_tokens")
    public val maxOutputTokens: Long = UnifiedExecDefaultMaxOutputTokens,
)

/**
 * JSON result returned by `exec_command` (and recorded in historical `write_stdin` commits).
 *
 * @property exitCode Nullable while the process is still running; `null` means
 * the command moved to the background under [sessionId].
 * @property sessionId Non-null when the command is still running in the background;
 * `null` when the command completed within the initial wait window.
 * @property chunkId Identifier of this output chunk, not a process/session ID.
 * @property wallTimeSeconds Elapsed time spent in this client call.
 * @property originalTokenCount Approximate token count before truncation for
 * this chunk, not a cumulative total for the process.
 * @property output Collected text, possibly shortened with a truncation marker
 * while [originalTokenCount] still describes the pre-truncation text.
 */
@Serializable
public data class UnifiedExecOutput(
    @SerialName("chunk_id")
    public val chunkId: String,
    @SerialName("wall_time_seconds")
    public val wallTimeSeconds: Double,
    @SerialName("exit_code")
    public val exitCode: Int? = null,
    @SerialName("session_id")
    public val sessionId: Int? = null,
    @SerialName("original_token_count")
    public val originalTokenCount: Long,
    public val output: String,
)

/** Invalid request, unavailable session, or local process failure reported by unified exec. */
public class UnifiedExecToolException(
    message: String,
) : IllegalArgumentException(message)
