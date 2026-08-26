package io.github.stream29.kodex.mcp.stdio

import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSink
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSource
import io.github.stream29.kodex.utils.processclient.ProcessClient
import io.github.stream29.kodex.utils.processclient.ProcessCommand
import io.github.stream29.kodex.utils.processclient.ProcessSession
import io.modelcontextprotocol.kotlin.sdk.client.CoroutineStdioSink
import io.modelcontextprotocol.kotlin.sdk.client.CoroutineStdioSource
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import kotlinx.io.Buffer
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Starts an MCP server process and returns a transport that owns that session. */
public suspend fun ProcessClient.openMcpStdioTransport(
    configuration: McpServerConfiguration.Stdio,
): Transport {
    val process = start(
        ProcessCommand(
            executable = configuration.command,
            arguments = configuration.args,
            workingDirectory = configuration.workingDirectory,
            environment = configuration.environment.mapValues { (_, secret) -> secret.value },
        ),
    )
    return try {
        ProcessOwnedTransport(
            delegate = StdioClientTransport(
                input = ProcessCoroutineStdioSource(process.stdout),
                output = ProcessCoroutineStdioSink(process.stdin),
                error = ProcessCoroutineStdioSource(process.stderr),
            ),
            process = process,
        )
    } catch (failure: Throwable) {
        process.close()
        throw failure
    }
}

private class ProcessCoroutineStdioSource(
    private val delegate: CoroutineRawSource,
) : CoroutineStdioSource {
    override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long =
        delegate.readAtMostTo(sink, byteCount)

    override suspend fun close() {
        delegate.close()
    }
}

private class ProcessCoroutineStdioSink(
    private val delegate: CoroutineRawSink,
) : CoroutineStdioSink {
    override suspend fun write(source: Buffer, byteCount: Long) {
        delegate.write(source, byteCount)
    }

    override suspend fun flush() {
        delegate.flush()
    }

    override suspend fun close() {
        delegate.close()
    }
}

@OptIn(ExperimentalAtomicApi::class)
private class ProcessOwnedTransport(
    private val delegate: Transport,
    private val process: ProcessSession,
) : Transport {
    private val processClosed = AtomicBoolean(false)

    override suspend fun start() {
        delegate.start()
    }

    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        delegate.send(message, options)
    }

    override suspend fun close() {
        try {
            closeProcess()
        } finally {
            delegate.close()
        }
    }

    override fun onClose(block: () -> Unit) {
        delegate.onClose {
            closeProcess()
            block()
        }
    }

    override fun onError(block: (Throwable) -> Unit) {
        delegate.onError(block)
    }

    override fun onMessage(block: suspend (JSONRPCMessage) -> Unit) {
        delegate.onMessage(block)
    }

    private fun closeProcess() {
        if (processClosed.compareAndSet(expectedValue = false, newValue = true)) {
            process.close()
        }
    }
}
