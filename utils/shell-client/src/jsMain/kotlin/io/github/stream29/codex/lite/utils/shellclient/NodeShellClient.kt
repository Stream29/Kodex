@file:Suppress("UnsafeCastFromDynamic")

package io.github.stream29.codex.lite.utils.shellclient

import js.array.toJsArray
import js.objects.Object
import js.objects.set
import js.objects.unsafeJso
import js.typedarrays.toByteArray
import node.buffer.Buffer
import node.childProcess.ChildProcessWithoutNullStreams
import node.childProcess.SpawnOptionsWithoutStdio
import node.childProcess.spawn
import node.events.EventListener
import node.events.EventType
import node.os.platform
import node.process.Process
import node.process.ProcessEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

private const val POSIX_SIGKILL: Double = 9.0

private val isWindowsNode: Boolean
    get() = platform().toString() == "win32"

/**
 * The generated `node:process` wrapper targets its unavailable CommonJS
 * `default` export. The Node runtime global retains the wrapper's [Process]
 * shape and exposes the process-group `kill` operation.
 */
@JsName("process")
private external val currentNodeProcess: Process

private external interface NodePty {
    fun spawn(
        file: String,
        args: Array<String>,
        options: NodePtySpawnOptions,
    ): NodePseudoTerminal
}

private val nodePty: NodePty by lazy {
    js("require('node-pty')")
}

private external interface NodePtySpawnOptions {
    var name: String
    var cols: Int
    var rows: Int
    var cwd: String
    var env: ProcessEnv?
}

private external interface NodePseudoTerminal {
    val pid: Int

    fun write(data: String)

    fun kill(signal: String? = definedExternally)

    fun pause()

    fun resume()

    fun onData(listener: (String) -> Unit): NodePtyDisposable

    fun onExit(listener: (NodePtyExit) -> Unit): NodePtyDisposable
}

private external interface NodePtyExit {
    val exitCode: Int
}

private external interface NodePtyDisposable {
    fun dispose()
}

public actual class ShellClient internal actual constructor(
    scope: CoroutineScope,
) :
    CoroutineScope by scope,
    AutoCloseable {

    public actual suspend fun start(command: ShellProcessCommand): ProcessSession {
        this@ShellClient.requireOpen()
        if (command.command.isBlank()) {
            throw ProcessException("Process command must not be blank.")
        }
        val invocation = command.shell.invocation(command.command, command.login)
        val environment = command.environment.toNodeEnvironmentOrNull()
        return try {
            if (command.tty) {
                NodePtyTransport(
                    invocation = invocation,
                    workingDirectory = command.workingDirectory.toString(),
                    environment = environment,
                ).createSession(this@ShellClient)
            } else {
                NodePipeTransport(
                    process = spawn(
                        invocation.executable,
                        (invocation.argumentsBeforeCommand + invocation.command).toJsArray(),
                        unsafeJso<SpawnOptionsWithoutStdio> {
                            cwd = command.workingDirectory.toString()
                            shell = false
                            windowsHide = true
                            detached = !isWindowsNode
                            this.env = environment
                        },
                    ),
                ).createSession(this@ShellClient)
            }
        } catch (error: Throwable) {
            throw ProcessException("Failed to start Node.js child process.", error)
        }
    }

    public actual override fun close() {
        cancel()
    }
}

private class NodePipeTransport(
    private val process: ChildProcessWithoutNullStreams,
) {
    fun createSession(parentScope: CoroutineScope): ProcessSession {
        val session = NodeProcessSession(
            parentScope = parentScope,
            writeInput = ::writeStdin,
            closeInput = ::closeStdin,
            terminate = ::terminateProcessTree,
            release = ::releaseResources,
        )
        process.requiredStdout.collectOutput(session, NodeProcessOutputStream.StandardOutput)
        process.requiredStderr.collectOutput(session, NodeProcessOutputStream.StandardError)
        process.on(EventType("error"), EventListener { error: Any? ->
            session.acceptFailure(ProcessException("Node.js child process failed: $error"))
        })
        process.requiredStdin.on(EventType("error"), EventListener { error: Any? ->
            session.acceptFailure(
                ProcessException("Failed to write to Node.js child process standard input: $error"),
            )
        })
        process.on(EventType("close"), EventListener { exitCode: Any?, _: Any? ->
            session.acceptExit((exitCode as? Number)?.toInt() ?: 1)
        })
        return session
    }

    private suspend fun writeStdin(text: String): Unit = suspendCancellableCoroutine { continuation ->
        try {
            process.requiredStdin.write(text) { error ->
                if (error == null) {
                    continuation.resume(Unit)
                } else {
                    continuation.resumeWithException(
                        ProcessException("Failed to write to Node.js child process standard input.", error),
                    )
                }
            }
        } catch (error: Throwable) {
            continuation.resumeWithException(
                ProcessException("Failed to write to Node.js child process standard input.", error),
            )
        }
    }

    private suspend fun closeStdin(): Unit = suspendCancellableCoroutine { continuation ->
        val stdin = process.requiredStdin
        if (stdin.destroyed || stdin.writableEnded) {
            continuation.resume(Unit)
            return@suspendCancellableCoroutine
        }
        try {
            stdin.end { continuation.resume(Unit) }
        } catch (error: Throwable) {
            continuation.resumeWithException(
                ProcessException("Failed to close Node.js child process standard input.", error),
            )
        }
    }

    private suspend fun terminateProcessTree() {
        val pid = process.pid ?: return
        terminateNodeProcessTree(pid) {
            process.kill(POSIX_SIGKILL)
        }
    }

    private fun releaseResources() {
        process.requiredStdin.destroy()
    }
}

private class NodePtyTransport(
    invocation: ShellInvocation,
    workingDirectory: String,
    environment: ProcessEnv?,
) {
    private val terminal: NodePseudoTerminal = nodePty.spawn(
        file = invocation.executable,
        args = buildList {
            addAll(invocation.argumentsBeforeCommand)
            add(invocation.command)
        }.toTypedArray(),
        options = unsafeJso {
            name = "xterm-256color"
            cols = DefaultPtyColumns
            rows = DefaultPtyRows
            cwd = workingDirectory
            env = environment
        },
    )

    fun createSession(parentScope: CoroutineScope): ProcessSession {
        val subscriptions: MutableList<NodePtyDisposable> = mutableListOf()
        val session = NodeProcessSession(
            parentScope = parentScope,
            writeInput = { text -> terminal.write(text.normalizedForNodePty()) },
            // Closing a PTY master would hang up its output. EOF remains an explicit terminal input.
            closeInput = {},
            terminate = ::terminateProcessTree,
            release = {
                subscriptions.forEach(NodePtyDisposable::dispose)
                if (isWindowsNode) terminal.kill()
            },
        )
        subscriptions += terminal.onData { output ->
            terminal.pause()
            session.acceptOutput(
                output.encodeToByteArray(),
                NodeProcessOutputStream.StandardOutput,
                terminal::resume,
            )
        }
        subscriptions += terminal.onExit { exit ->
            session.acceptExit(exit.exitCode)
        }
        return session
    }

    private suspend fun terminateProcessTree() {
        terminateNodeProcessTree(terminal.pid.toDouble()) {
            if (isWindowsNode) {
                terminal.kill()
            } else {
                terminal.kill("SIGKILL")
            }
        }
    }
}

private suspend fun terminateNodeProcessTree(pid: Double, terminateChild: () -> Unit) {
    if (isWindowsNode) {
        if (withTimeoutOrNull(3.seconds) { terminateWindowsProcessTree(pid, terminateChild) } == null) {
            terminateChild()
        }
        return
    }
    try {
        if (currentNodeProcess.kill(-pid, POSIX_SIGKILL)) return
    } catch (_: Throwable) {
        // Fall through to the direct child when its process group is already gone.
    }
    terminateChild()
}

private suspend fun terminateWindowsProcessTree(pid: Double, fallback: () -> Unit): Unit =
    suspendCancellableCoroutine { continuation ->
        fun finish() {
            if (continuation.isActive) {
                continuation.resume(Unit)
            }
        }
        try {
            spawn(
                "taskkill",
                listOf("/PID", pid.toInt().toString(), "/T", "/F").toJsArray(),
            ).also { taskKill ->
                taskKill.on(EventType("close"), EventListener { _: Any?, _: Any? -> finish() })
                taskKill.on(EventType("error"), EventListener { _: Any? ->
                    fallback()
                    finish()
                })
            }
        } catch (_: Throwable) {
            fallback()
            finish()
        }
    }

private fun node.stream.Readable.collectOutput(
    session: NodeProcessSession,
    stream: NodeProcessOutputStream,
) {
    on(EventType("data"), EventListener { chunk: Any? ->
        val bytes: ByteArray = when (chunk) {
            is String -> chunk.encodeToByteArray()
            is Buffer<*> -> chunk.toByteArray()
            else -> chunk.toString().encodeToByteArray()
        }
        pause()
        session.acceptOutput(bytes, stream, ::resume)
    })
}

private fun String.normalizedForNodePty(): String =
    if (isWindowsNode) {
        replace("\r\n", "\r").replace("\n", "\r").replace("\b", "\u007f")
    } else {
        this
    }

private fun Map<String, String>.toNodeEnvironmentOrNull(): ProcessEnv? {
    if (isEmpty()) return null
    val result = Object.assign(unsafeJso<ProcessEnv>(), currentNodeProcess.env)
    val inheritedNames = if (isWindowsNode) {
        @Suppress("UNCHECKED_CAST")
        val names = js("Object.keys(result)") as Array<String>
        names.associateBy(String::lowercase)
    } else {
        emptyMap()
    }
    forEach { (name, value) ->
        result[inheritedNames[name.lowercase()] ?: name] = value
    }
    return result
}
