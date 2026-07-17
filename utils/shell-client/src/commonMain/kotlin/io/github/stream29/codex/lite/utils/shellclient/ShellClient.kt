package io.github.stream29.codex.lite.utils.shellclient

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlin.coroutines.CoroutineContext

/**
 * Stateful local shell-process client.
 *
 * A client owns every [ProcessSession] it starts. Calling [close] cancels all
 * owned sessions and their child process-I/O scopes.
 */
public expect class ShellClient() : CoroutineScope, AutoCloseable {
    override val coroutineContext: CoroutineContext

    /** Starts [command] in a session owned by this client. */
    public suspend fun start(command: ShellProcessCommand): ProcessSession

    override fun close()
}

internal fun CoroutineScope.requireOpen() {
    if (!isActive) throw ProcessException("Shell client is closed.")
}
