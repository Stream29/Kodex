package io.github.stream29.kodex.utils.shellclient

import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlin.coroutines.CoroutineContext

internal expect class PlatformShellClient(
    scope: CoroutineScope,
) : ShellClient {
    override val coroutineContext: CoroutineContext

    /** Starts [command] in a session owned by this client. */
    override suspend fun start(command: ShellProcessCommand): ProcessSession

    override fun close()
}

/** Creates an independently cancellable shell client under this scope. */
public fun CoroutineScope.ShellClient(): ShellClient {
    return PlatformShellClient(supervisorChildScope())
}

internal fun CoroutineScope.requireOpen() {
    if (!isActive) throw ProcessException("Shell client is closed.")
}
