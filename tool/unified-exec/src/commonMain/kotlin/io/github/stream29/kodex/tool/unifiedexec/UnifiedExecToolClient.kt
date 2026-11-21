@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.tool.unifiedexec

import io.github.stream29.kodex.utils.shellclient.ProcessException
import io.github.stream29.kodex.utils.shellclient.ProcessSession
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellClient
import io.github.stream29.kodex.utils.shellclient.ShellProcessCommand
import io.github.stream29.kodex.utils.shellclient.ShellSettings
import io.github.stream29.kodex.utils.shellclient.StdoutSlice
import io.github.stream29.kodex.utils.shellclient.renderedBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal const val UnifiedExecWindowsMinimumYieldTimeMillis: Long = 10_000L

public actual class UnifiedExecToolClient internal constructor(
    private val workingDirectoryProvider: suspend () -> Path,
    private val settingsProvider: suspend () -> ShellSettings,
    private val environmentProvider: suspend () -> Map<String, String> = { emptyMap() },
    private val shellClient: ShellClient,
) : UnifiedExecClient {
    private val registryMutex: Mutex = Mutex()
    private val mutableSessions: MutableStateFlow<Map<Int, ManagedProcessSession>> =
        MutableStateFlow(emptyMap())

    public actual override val activeSessions: StateFlow<Map<Int, UnifiedExecProcessSession>> =
        mutableSessions.asStateFlow()

    public actual override suspend fun execCommand(arguments: ExecCommandArguments): UnifiedExecOutput {
        arguments.validate()
        val started = TimeSource.Monotonic.markNow()
        val shellCommand = runUnifiedExecProcessOperation {
            arguments.toShellProcessCommand(
                defaultWorkingDirectory = workingDirectoryProvider(),
                defaultShell = settingsProvider().shell,
                environment = environmentProvider(),
            )
        }
        val session = runUnifiedExecProcessOperation {
            shellClient.start(shellCommand)
        }
        val managed = try {
            registryMutex.withLock {
                discardInactiveSessions()
                val sessions = mutableSessions.value
                if (!session.scope.isActive && !session.exitCode.isCompleted) {
                    throw UnifiedExecToolException("Unified exec tool client is closed.")
                }
                ManagedProcessSession(
                    sessionId = allocateSessionId(sessions),
                    cwd = shellCommand.workingDirectory,
                    arguments = arguments,
                    session = session,
                ).also { created ->
                    mutableSessions.update { current -> current + (created.sessionId to created) }
                    created.retentionJob = launchExitWatcher(created)
                }
            }
        } catch (error: Throwable) {
            session.close()
            throw error
        }

        return try {
            val read = runUnifiedExecProcessOperation {
                managed.session.awaitInitialOutput(arguments.yieldTimeMillis.normalizedExecYieldTime())
            }
            outputFor(managed, read, started.elapsedNow(), arguments.maxOutputTokens)
        } catch (error: Throwable) {
            removeAndClose(managed)
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

    private fun launchExitWatcher(managed: ManagedProcessSession): Job =
        shellClient.launch(start = CoroutineStart.UNDISPATCHED) {
            val code = try {
                managed.session.exitCode.await()
            } catch (cancellation: CancellationException) {
                if (!shellClient.isActive) {
                    evictIfSame(managed)
                }
                throw cancellation
            } catch (_: Throwable) {
                evictIfSame(managed)
                return@launch
            }
            managed.publishExitCode(code)
            delay(UnifiedExecCompletedSessionRetentionMillis.milliseconds)
            evictIfSame(managed)
        }

    private fun evictIfSame(managed: ManagedProcessSession) {
        mutableSessions.update { current ->
            if (current[managed.sessionId] === managed) current - managed.sessionId else current
        }
    }

    private suspend fun removeAndClose(managed: ManagedProcessSession) {
        managed.retentionJob?.cancel()
        registryMutex.withLock {
            evictIfSame(managed)
        }
        managed.session.close()
    }

    public actual override fun close() {
        mutableSessions.value = emptyMap()
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
}

public actual fun CoroutineScope.UnifiedExecToolClient(
    settingsProvider: suspend () -> ShellSettings,
    workingDirectoryProvider: suspend () -> Path,
    environmentProvider: suspend () -> Map<String, String>,
): UnifiedExecToolClient =
    UnifiedExecToolClient(
        workingDirectoryProvider = workingDirectoryProvider,
        settingsProvider = settingsProvider,
        environmentProvider = environmentProvider,
        shellClient = ShellClient(),
    )

internal class ManagedProcessSession(
    override val sessionId: Int,
    override val cwd: Path,
    /** The original `exec_command` arguments that started this session. */
    override val arguments: ExecCommandArguments,
    internal val session: ProcessSession,
) : UnifiedExecProcessSession {
    @OptIn(ExperimentalUuidApi::class)
    override val registrationNonce: String = Uuid.random().toString()
    internal var retentionJob: Job? = null
    private val mutableExitCode: MutableStateFlow<Int?> = MutableStateFlow(null)
    override val exitCode: StateFlow<Int?> = mutableExitCode.asStateFlow()

    internal fun publishExitCode(code: Int) {
        mutableExitCode.value = code
    }

    override suspend fun readOutput(offset: ULong, limit: UInt): StdoutSlice =
        runUnifiedExecProcessOperation {
            session.stdout.read(offset = offset, limit = limit)
        }

    override suspend fun writeStdin(chars: String, closeInput: Boolean) {
        runUnifiedExecProcessOperation {
            if (chars.isNotEmpty()) {
                session.stdin.send(chars)
            }
            if (closeInput) {
                session.stdin.close()
            }
        }
    }

    override fun close() {
        session.close()
    }
}

private suspend fun <Result> runUnifiedExecProcessOperation(block: suspend () -> Result): Result =
    try {
        block()
    } catch (failure: ProcessException) {
        throw UnifiedExecToolException(failure.message ?: "Local process operation failed.")
    } catch (failure: ClosedSendChannelException) {
        throw UnifiedExecToolException(failure.message ?: "Process standard input is closed.")
    }

private data class ProcessSessionOutput(
    val output: String,
    val exitCode: Int?,
)

private suspend fun ProcessSession.awaitInitialOutput(yieldTime: Duration): ProcessSessionOutput {
    // ShellClient keeps collecting bounded output and flushes it before completing
    // exitCode. Output arrival alone must not end the tool's yield window.
    val completedExitCode = withTimeoutOrNull(yieldTime) { exitCode.await() }
    val output = stdout.read(offset = 0uL, limit = UInt.MAX_VALUE).renderedBytes().decodeToString()
    return ProcessSessionOutput(output = output, exitCode = completedExitCode)
}

private fun ExecCommandArguments.validate() {
    if (command.isBlank()) throw UnifiedExecToolException("`cmd` must not be blank.")
    if (yieldTimeMillis < 0) throw UnifiedExecToolException("`yield_time_ms` must not be negative.")
    if (maxOutputTokens < 0) throw UnifiedExecToolException("`max_output_tokens` must not be negative.")
}

private fun ExecCommandArguments.toShellProcessCommand(
    defaultWorkingDirectory: Path,
    defaultShell: Shell,
    environment: Map<String, String>,
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
        tty = true,
        environment = environment,
    )

private fun Long.normalizedExecYieldTime(): Duration =
    normalizedExecYieldTimeMillis(this, execCommandHostPlatform).milliseconds

internal fun normalizedExecYieldTimeMillis(
    requestedMillis: Long,
    platform: ExecCommandHostPlatform,
): Long =
    requestedMillis.coerceIn(
        minimumValue = if (platform == ExecCommandHostPlatform.Windows) {
            UnifiedExecWindowsMinimumYieldTimeMillis
        } else {
            UnifiedExecMinimumYieldTimeMillis
        },
        maximumValue = UnifiedExecMaximumYieldTimeMillis,
    )

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
