package io.github.stream29.codex.lite.utils.processclient

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.asSink
import kotlinx.io.asSource
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

public actual class ProcessClient internal actual constructor(
    scope: CoroutineScope,
) :
    CoroutineScope by scope,
    AutoCloseable {

    public actual suspend fun start(command: ProcessCommand): ProcessSession =
        withContext(Dispatchers.IO) {
            this@ProcessClient.requireOpen()
            val process = try {
                ProcessBuilder(listOf(command.executable) + command.arguments)
                    .redirectErrorStream(false)
                    .directory(File(command.workingDirectory.toString()))
                    .apply { environment().putAll(command.environment) }
                    .start()
            } catch (failure: IOException) {
                throw ProcessException("Failed to start process with ${command.executable}.", failure)
            }
            JvmProcessSession(process, this@ProcessClient)
        }

    public actual override fun close() {
        cancel()
    }
}

@OptIn(ExperimentalAtomicApi::class)
private class JvmProcessSession(
    private val process: Process,
    ownerScope: CoroutineScope,
) : ProcessSession {
    override val stdin: RawSink = process.outputStream.asSink()
    override val stdout: RawSource = process.inputStream.asSource()
    override val stderr: RawSource = process.errorStream.asSource()
    override val exitCode: Deferred<Int>
        field = CompletableDeferred()
    private val closed = AtomicBoolean(false)
    private val cancellationGuard = ownerScope.lazyProcessCancellationGuard(::close)

    init {
        cancellationGuard.start()
        ownerScope.launch(Dispatchers.IO) {
            try {
                exitCode.complete(process.waitFor())
            } catch (failure: Throwable) {
                exitCode.completeExceptionally(failure)
            }
        }.invokeOnCompletion { failure ->
            if (failure != null) close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        cancellationGuard.cancel()
        if (process.isAlive) {
            val descendants = runCatching { process.descendants().toList() }.getOrDefault(emptyList())
            descendants.asReversed().forEach { child -> runCatching { child.destroy() } }
            runCatching { process.destroy() }
            if (!runCatching {
                    process.waitFor(ProcessExitGraceMillis, TimeUnit.MILLISECONDS)
                }.getOrDefault(true)
            ) {
                descendants.asReversed().forEach { child -> runCatching { child.destroyForcibly() } }
                runCatching { process.destroyForcibly() }
            }
        }
        runCatching { stdin.close() }
        runCatching { exitCode.complete(process.waitFor()) }
        runCatching { stdout.close() }
        runCatching { stderr.close() }
    }
}

private const val ProcessExitGraceMillis: Long = 500L
