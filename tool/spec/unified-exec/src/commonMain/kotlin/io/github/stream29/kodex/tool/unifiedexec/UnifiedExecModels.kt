package io.github.stream29.kodex.tool.unifiedexec

import io.github.stream29.kodex.utils.shellclient.Shell
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Default wait before an `exec_command` call yields its first output. */
public const val UnifiedExecDefaultYieldTimeMillis: Long = 10_000L

/** Default requested `write_stdin` yield; empty polls use a longer minimum. */
public const val UnifiedExecDefaultWriteYieldTimeMillis: Long = 250L

/** Default approximate output-token budget for each returned chunk. */
public const val UnifiedExecDefaultMaxOutputTokens: Long = 10_000L

/**
 * Arguments accepted by the `exec_command` function tool.
 *
 * @property command Nonblank command text to execute in the selected shell.
 * @property workdir Nullable because a command normally uses the session
 * working directory; `null` or an empty path uses that directory, while a
 * relative path resolves against it.
 * @property shell Nullable because the host selects its current configured
 * shell when no shell is given; `null` uses that shell at process start.
 * @property yieldTimeMillis Requested wait before returning available output;
 * the host may clamp it to supported limits. Negative values are invalid.
 * @property maxOutputTokens Approximate token budget for the returned output;
 * negative values are invalid and the byte ceiling still applies.
 */
@Serializable
public data class ExecCommandArguments(
    @SerialName("cmd")
    public val command: String,
    public val workdir: String? = null,
    public val shell: Shell? = null,
    /** Whether to allocate a terminal rather than ordinary process pipes. */
    public val tty: Boolean = false,
    @SerialName("yield_time_ms")
    public val yieldTimeMillis: Long = UnifiedExecDefaultYieldTimeMillis,
    @SerialName("max_output_tokens")
    public val maxOutputTokens: Long = UnifiedExecDefaultMaxOutputTokens,
)

/**
 * Arguments accepted by the `write_stdin` function tool.
 *
 * @property sessionId Positive ID of a session still registered for final
 * output, even when its process has already completed.
 * @property chars Input to send; an empty string polls without writing.
 * @property yieldTimeMillis Requested wait for available output; an empty poll
 * uses a longer minimum than a nonempty write. Negative values are invalid.
 * @property maxOutputTokens Approximate per-result output budget; negative
 * values are invalid and the byte ceiling still applies.
 */
@Serializable
public data class WriteStdinArguments(
    @SerialName("session_id")
    public val sessionId: Int,
    public val chars: String = "",
    @SerialName("yield_time_ms")
    public val yieldTimeMillis: Long = UnifiedExecDefaultWriteYieldTimeMillis,
    @SerialName("max_output_tokens")
    public val maxOutputTokens: Long = UnifiedExecDefaultMaxOutputTokens,
)

/**
 * JSON result returned by `exec_command` and `write_stdin`.
 *
 * @property exitCode Nullable while the process is still running; `null` means
 * callers must use [sessionId] with `write_stdin` to obtain a final exit code.
 * @property sessionId Nullable after process completion; `null` means this
 * result is final and the session can no longer receive `write_stdin` calls.
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
