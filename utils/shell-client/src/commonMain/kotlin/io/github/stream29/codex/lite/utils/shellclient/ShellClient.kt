package io.github.stream29.codex.lite.utils.shellclient

import io.github.stream29.codex.lite.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlin.coroutines.CoroutineContext

/**
 * Stateful local shell-process client.
 *
 * A client created by [CoroutineScope.ShellClient] is a child of that scope and
 * owns every [ProcessSession] it starts. Calling [close] cancels all owned
 * sessions and their child process-I/O scopes.
 */
public expect class ShellClient internal constructor(
    scope: CoroutineScope,
) : CoroutineScope, AutoCloseable {
    override val coroutineContext: CoroutineContext

    /** Starts [command] in a session owned by this client. */
    public suspend fun start(command: ShellProcessCommand): ProcessSession

    override fun close()
}

/** Creates an independently cancellable shell client under this scope. */
public fun CoroutineScope.ShellClient(): ShellClient {
    return ShellClient(supervisorChildScope())
}

internal fun CoroutineScope.requireOpen() {
    if (!isActive) throw ProcessException("Shell client is closed.")
}
