package io.github.stream29.codex.lite.tool.unifiedexec

import io.github.stream29.codex.lite.utils.shellclient.Shell
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Arguments accepted by the `exec_command` function tool.
 *
 * @property workdir Nullable because a command normally uses the session
 * working directory; `null` means use that directory.
 * @property shell Nullable because the host selects its normal shell when no
 * shell is given; `null` means use the platform default shell.
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

/** Arguments accepted by the `write_stdin` function tool. */
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

/** A user-correctable error returned from the unified-exec tool boundary. */
public class UnifiedExecToolException(
    message: String,
) : IllegalArgumentException(message)
