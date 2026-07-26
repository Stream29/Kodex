package io.github.stream29.codex.lite.utils.shellclient

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.newCoroutineContext
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
@OptIn(ExperimentalCoroutinesApi::class)
public fun CoroutineScope.ShellClient(): ShellClient {
    val parentJob = requireNotNull(coroutineContext[Job]) {
        "ShellClient requires an owning CoroutineScope with a Job."
    }
    return ShellClient(
        CoroutineScope(newCoroutineContext(SupervisorJob(parentJob))),
    )
}

internal fun CoroutineScope.requireOpen() {
    if (!isActive) throw ProcessException("Shell client is closed.")
}
