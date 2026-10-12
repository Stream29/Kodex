package io.github.stream29.kodex.utils.shellclient

import kotlinx.coroutines.CoroutineScope

/**
 * Stateful local shell-process owner, independently cancellable under its parent scope.
 *
 * Every started [ProcessSession] belongs to this client's child scope. Closing
 * or cancelling the client aborts owned sessions and their process-I/O scopes.
 * Session [ProcessSession.close] instead preserves exit observation while
 * requesting process-tree termination.
 */
public interface ShellClient : CoroutineScope, AutoCloseable {
    /**
     * Starts [command] using its explicitly selected shell and pipe/PTY mode.
     *
     * @throws ProcessException when the client is closed, the command is blank,
     * or the platform cannot create the requested PTY; Node also wraps platform
     * startup failures in this exception.
     * @throws io.github.stream29.kodex.utils.processclient.ProcessException when
     * a pipe backend forwards a direct-process startup failure.
     * @throws kotlinx.coroutines.CancellationException when suspendable startup
     * is cancelled outside a platform failure-wrapping boundary.
     */
    public suspend fun start(command: ShellProcessCommand): ProcessSession

    /** Cancels the client and all owned process sessions. */
    override fun close()
}
