package io.github.stream29.kodex.tool.unifiedexec

import io.github.stream29.kodex.utils.shellclient.ProcessException
import io.github.stream29.kodex.utils.shellclient.ProcessSession
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellClient
import io.github.stream29.kodex.utils.shellclient.ShellProcessCommand
import io.github.stream29.kodex.utils.shellclient.ShellSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

public const val UnifiedExecMinimumYieldTimeMillis: Long = 250L
public const val UnifiedExecMaximumYieldTimeMillis: Long = 30_000L
public const val UnifiedExecMinimumEmptyPollYieldTimeMillis: Long = 5_000L
public const val UnifiedExecMaximumEmptyPollYieldTimeMillis: Long = 300_000L
public const val UnifiedExecMaximumOutputByteCount: Int = 1_024 * 1_024
public const val UnifiedExecMaximumSessionCount: Int = 64

/**
 * Stateful local process manager shared by `exec_command` and `write_stdin`.
 *
 * This client owns one [ShellClient], which owns every process session it
 * creates. Closing this client closes that shell client and cancels all active
 * sessions. Commands always use the selected shell's login initialization;
 * model calls cannot disable it. A command without an explicit shell captures
 * the current [ShellSettings.shell] value when its process starts.
 */
public class UnifiedExecToolClient internal constructor(
    private val workingDirectoryProvider: suspend () -> Path,
    private val settingsProvider: suspend () -> ShellSettings,
    private val shellClient: ShellClient,
) : AutoCloseable {
    private val registryMutex: Mutex = Mutex()
    private val mutableSessions: MutableStateFlow<Map<Int, ManagedProcessSession>> =
        MutableStateFlow(emptyMap())

    /**
     * Sessions that can still be read through `write_stdin`, keyed by their
     * public session identifier. A session can report [UnifiedExecProcessSession.completed]
     * without leaving this snapshot: its final output still needs to be read.
     */
    public val activeSessions: StateFlow<Map<Int, UnifiedExecProcessSession>> =
        mutableSessions.asStateFlow()

    public suspend fun execCommand(arguments: ExecCommandArguments): UnifiedExecOutput {
        arguments.validate()
        val started = TimeSource.Monotonic.markNow()
        val session = runProcessOperation {
            shellClient.start(
                arguments.toShellProcessCommand(
                    workingDirectoryProvider(),
                    settingsProvider().shell,
                ),
            )
        }
        val managed = try {
            registryMutex.withLock {
                discardInactiveSessions()
                val sessions = mutableSessions.value
                if (!session.scope.isActive && !session.exitCode.isCompleted) {
                    throw UnifiedExecToolException("Unified exec tool client is closed.")
                }
                if (sessions.size >= UnifiedExecMaximumSessionCount) {
                    throw UnifiedExecToolException(
                        "At most $UnifiedExecMaximumSessionCount process sessions may be active at once.",
                    )
                }
                ManagedProcessSession(
                    sessionId = allocateSessionId(sessions),
                    arguments = arguments,
                    session = session,
                ).also {
                    mutableSessions.update { current -> current + (it.sessionId to it) }
                }
            }
        } catch (error: Throwable) {
            session.close()
            throw error
        }

        return try {
            val read = managed.mutex.withLock {
                runProcessOperation {
                    managed.session.readOutput(arguments.yieldTimeMillis.normalizedExecYieldTime())
                }
            }
            outputFor(managed, read, started.elapsedNow(), arguments.maxOutputTokens)
        } catch (error: Throwable) {
            cancelSession(managed)
            throw error
        }
    }

    public suspend fun writeStdin(arguments: WriteStdinArguments): UnifiedExecOutput {
        arguments.validate()
        val managed = sessionFor(arguments.sessionId)
        val started = TimeSource.Monotonic.markNow()
        return try {
            val read = managed.mutex.withLock {
                runProcessOperation {
                    if (arguments.chars.isNotEmpty()) managed.session.stdin.send(arguments.chars)
                    managed.session.readOutput(
                        arguments.yieldTimeMillis.normalizedWriteYieldTime(arguments.chars.isEmpty()),
                    )
                }
            }
            outputFor(managed, read, started.elapsedNow(), arguments.maxOutputTokens)
        } catch (error: Throwable) {
            cancelSession(managed)
            throw error
        }
    }

    private suspend fun outputFor(
        managed: ManagedProcessSession,
        read: ProcessSessionOutput,
        wallTime: Duration,
        maxOutputTokens: Long,
    ): UnifiedExecOutput {
        val sessionId = if (read.exitCode == null) {
            managed.sessionId
        } else {
            removeAndClose(managed)
            null
        }
        val truncated = read.output.truncatedToTokenBudget(maxOutputTokens)
        return UnifiedExecOutput(
            chunkId = nextChunkId(),
            wallTimeSeconds = wallTime.inWholeNanoseconds / 1_000_000_000.0,
            exitCode = read.exitCode,
            sessionId = sessionId,
            originalTokenCount = truncated.originalTokenCount,
            output = truncated.text,
        )
    }

    private suspend fun sessionFor(sessionId: Int): ManagedProcessSession =
        registryMutex.withLock {
            val managed = mutableSessions.value[sessionId]
                ?: throw UnifiedExecToolException("Unknown process session_id: $sessionId.")
            if (!managed.session.scope.isActive && !managed.session.exitCode.isCompleted) {
                mutableSessions.update { current ->
                    if (current[sessionId] === managed) current - sessionId else current
                }
                throw UnifiedExecToolException("Unknown process session_id: $sessionId.")
            }
            managed
        }

    private suspend fun removeAndClose(managed: ManagedProcessSession) {
        registryMutex.withLock {
            mutableSessions.update { current ->
                if (current[managed.sessionId] === managed) current - managed.sessionId else current
            }
        }
        managed.session.close()
    }

    private suspend fun cancelSession(managed: ManagedProcessSession) {
        removeAndClose(managed)
    }

    override fun close() {
        shellClient.close()
    }

    private fun discardInactiveSessions() {
        mutableSessions.update { current ->
            current.filterValues { managed ->
                managed.session.scope.isActive || managed.session.exitCode.isCompleted
            }
        }
    }

    private fun allocateSessionId(sessions: Map<Int, ManagedProcessSession>): Int {
        repeat(Int.MAX_VALUE) {
            val candidate = Random.nextInt(Int.MAX_VALUE) + 1
            if (candidate !in sessions) return candidate
        }
        throw UnifiedExecToolException("No process session identifiers are available.")
    }

    private suspend fun <Result> runProcessOperation(block: suspend () -> Result): Result =
        try {
            block()
        } catch (failure: ProcessException) {
            throw UnifiedExecToolException(failure.message ?: "Local process operation failed.")
        }
}

/** Creates a unified-exec client with a dedicated shell client under this scope. */
public fun CoroutineScope.UnifiedExecToolClient(
    settingsProvider: suspend () -> ShellSettings,
    workingDirectoryProvider: suspend () -> Path = { Path(".") },
): UnifiedExecToolClient =
    UnifiedExecToolClient(
        workingDirectoryProvider = workingDirectoryProvider,
        settingsProvider = settingsProvider,
        shellClient = ShellClient(),
    )

internal class ManagedProcessSession(
    override val sessionId: Int,
    /** The original `exec_command` arguments that started this session. */
    override val arguments: ExecCommandArguments,
    internal val session: ProcessSession,
) : UnifiedExecProcessSession {
    internal val mutex: Mutex = Mutex()
    private val mutableCompleted: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val completed: StateFlow<Boolean> = mutableCompleted.asStateFlow()

    init {
        session.scope.launch {
            session.exitCode.await()
            mutableCompleted.value = true
        }
    }

    override fun close() {
        session.close()
    }
}

/**
 * Observable control surface for one active unified-exec process session.
 *
 * Implementations retain the process and synchronization details; consumers
 * can use the original command and [completed] state for presentation. [close]
 * requests process-tree termination but leaves the session registered so its
 * final output and exit code remain readable through `write_stdin`.
 */
public interface UnifiedExecProcessSession : AutoCloseable {
    public val sessionId: Int
    public val arguments: ExecCommandArguments
    public val completed: StateFlow<Boolean>

    override fun close()
}

private data class ProcessSessionOutput(
    val output: String,
    val exitCode: Int?,
)

private suspend fun ProcessSession.readOutput(yieldTime: Duration): ProcessSessionOutput {
    val output = stdout.read(yieldTime).renderedBytes().decodeToString()
    val completedExitCode = if (this.exitCode.isCompleted) this.exitCode.await() else null
    return ProcessSessionOutput(output = output, exitCode = completedExitCode)
}

private fun ExecCommandArguments.validate() {
    if (command.isBlank()) throw UnifiedExecToolException("`cmd` must not be blank.")
    if (yieldTimeMillis < 0) throw UnifiedExecToolException("`yield_time_ms` must not be negative.")
    if (maxOutputTokens < 0) throw UnifiedExecToolException("`max_output_tokens` must not be negative.")
}

private fun WriteStdinArguments.validate() {
    if (sessionId < 1) throw UnifiedExecToolException("`session_id` must be positive.")
    if (yieldTimeMillis < 0) throw UnifiedExecToolException("`yield_time_ms` must not be negative.")
    if (maxOutputTokens < 0) throw UnifiedExecToolException("`max_output_tokens` must not be negative.")
}

private fun ExecCommandArguments.toShellProcessCommand(
    defaultWorkingDirectory: Path,
    defaultShell: Shell,
): ShellProcessCommand =
    ShellProcessCommand(
        command = command,
        workingDirectory = workdir
            ?.takeIf(String::isNotEmpty)
            ?.let { value ->
                val requested = Path(value)
                if (requested.isAbsolute) requested else Path(defaultWorkingDirectory, value)
            }
            ?: defaultWorkingDirectory,
        shell = shell ?: defaultShell,
        login = true,
        tty = tty,
    )

private fun Long.normalizedExecYieldTime(): Duration =
    coerceIn(UnifiedExecMinimumYieldTimeMillis, UnifiedExecMaximumYieldTimeMillis).milliseconds

private fun Long.normalizedWriteYieldTime(isEmptyPoll: Boolean): Duration =
    if (isEmptyPoll) {
        coerceIn(UnifiedExecMinimumEmptyPollYieldTimeMillis, UnifiedExecMaximumEmptyPollYieldTimeMillis)
    } else {
        coerceIn(UnifiedExecMinimumYieldTimeMillis, UnifiedExecMaximumYieldTimeMillis)
    }.milliseconds

private data class TruncatedProcessOutput(
    val text: String,
    val originalTokenCount: Long,
)

private fun String.truncatedToTokenBudget(maxOutputTokens: Long): TruncatedProcessOutput {
    val originalByteCount = encodeToByteArray().size
    val originalTokenCount = originalByteCount.approximateTokenCount()
    val byteBudget = minOf(
        maxOutputTokens.coerceAtMost(UnifiedExecMaximumOutputByteCount.toLong() / 4) * 4,
        UnifiedExecMaximumOutputByteCount.toLong(),
    ).toInt()
    if (originalByteCount <= byteBudget) {
        return TruncatedProcessOutput(this, originalTokenCount)
    }
    if (byteBudget == 0) {
        return TruncatedProcessOutput("...${originalTokenCount} tokens truncated...", originalTokenCount)
    }

    val prefix = prefixWithinUtf8Budget(byteBudget / 2)
    val suffix = suffixWithinUtf8Budget(byteBudget - byteBudget / 2)
    val omittedTokens = (originalByteCount - byteBudget).approximateTokenCount()
    return TruncatedProcessOutput(
        text = "$prefix...$omittedTokens tokens truncated...$suffix",
        originalTokenCount = originalTokenCount,
    )
}

private fun String.prefixWithinUtf8Budget(byteBudget: Int): String = buildString {
    val bytes = this@prefixWithinUtf8Budget.encodeToByteArray()
    var end = minOf(bytes.size, byteBudget)
    while (end in 1 until bytes.size && bytes[end].isUtf8ContinuationByte()) {
        end -= 1
    }
    append(bytes.decodeToString(endIndex = end))
}

private fun String.suffixWithinUtf8Budget(byteBudget: Int): String = buildString {
    val bytes = this@suffixWithinUtf8Budget.encodeToByteArray()
    var start = (bytes.size - byteBudget).coerceAtLeast(0)
    while (start < bytes.size && bytes[start].isUtf8ContinuationByte()) {
        start += 1
    }
    append(bytes.decodeToString(startIndex = start))
}

private fun Byte.isUtf8ContinuationByte(): Boolean =
    toInt() and 0b1100_0000 == 0b1000_0000

private fun Int.approximateTokenCount(): Long =
    (toLong() + 3L) / 4L

private fun nextChunkId(): String = buildString(6) {
    repeat(6) { append(Random.nextInt(16).toString(16)) }
}
