package io.github.stream29.kodex.hook.impl

import io.github.stream29.kodex.utils.shellclient.ProcessSession
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellClient
import io.github.stream29.kodex.utils.shellclient.ShellProcessCommand
import io.github.stream29.kodex.utils.shellclient.StdoutBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import kotlin.time.Duration.Companion.seconds

/**
 * Raw result of one command handler.
 *
 * @property exitCode Nullable because startup, timeout, or observation failure
 * can leave no meaningful handler exit status; `null` means no status is available.
 */
internal data class HookRawResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
)

internal suspend fun ShellClient.runHookCommand(
    command: String,
    inputJson: String,
    cwd: Path,
): HookRawResult =
    try {
        withTimeoutOrNull(DefaultHookTimeoutSeconds.seconds) {
            start(
                ShellProcessCommand(
                    command = command,
                    workingDirectory = cwd,
                    shell = Shell.default,
                    environment = emptyMap(),
                ),
            ).use { process ->
                process.stdin.send(inputJson)
                process.stdin.close()
                val exitCode = process.exitCode.await()
                process.toRawResult(exitCode)
            }
        } ?: HookRawResult(exitCode = null, stdout = "", stderr = "")
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Throwable) {
        HookRawResult(exitCode = null, stdout = "", stderr = "")
    }

/**
 * @param exitCode Final process status, or `null` when no reliable status is available.
 */
private suspend fun ProcessSession.toRawResult(
    exitCode: Int?,
): HookRawResult {
    val stdout = standardOutput.captureOutput()
    val stderr = standardError.captureOutput()
    return HookRawResult(
        exitCode = exitCode.takeIf {
            stdout != null &&
                stderr != null &&
                !stdout.truncated &&
                !stderr.truncated
        },
        stdout = stdout?.text.orEmpty(),
        stderr = stderr?.text.orEmpty(),
    )
}

/** @return Captured output, or `null` when the stream cannot be observed. */
private suspend fun StdoutBuffer.captureOutput(): CapturedHookOutput? = try {
    val snapshot = drain()
    CapturedHookOutput(
        text = snapshot.renderedBytes().decodeToString(),
        truncated = snapshot.omittedByteCount > 0L,
    )
} catch (failure: CancellationException) {
    throw failure
} catch (_: Throwable) {
    null
}

private data class CapturedHookOutput(
    val text: String,
    val truncated: Boolean,
)

private const val DefaultHookTimeoutSeconds: Long = 600L
