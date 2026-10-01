package io.github.stream29.kodex.utils.shellclient

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.SendChannel
import kotlinx.io.files.Path

/** Model-facing shell selection and command invocation. */
public data class ShellCommandSpec(
    public val command: String,
    public val workingDirectory: Path = Path("."),
    public val shellPath: Path? = null,
    public val environment: Map<String, String> = emptyMap(),
)

/** Minimal lifecycle contract for a shell process implementation. */
public interface ShellSessionSpec : AutoCloseable {
    public val stdin: SendChannel<String>
    public val exitCode: Deferred<Int>
    override fun close()
}

/** Replaceable boundary for launching commands in an explicitly selected shell. */
public interface ShellClientSpec : AutoCloseable {
    public suspend fun start(command: ShellCommandSpec): ShellSessionSpec
    override fun close()
}
