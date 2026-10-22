@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.app.settings

import io.github.stream29.kodex.app.settings.contract.McpServerSettingsState
import io.github.stream29.kodex.mcp.contract.McpManagedServerState
import kotlinx.coroutines.CoroutineScope

/** Frontend-safe MCP projection; no backend manager ownership is transferred to the renderer. */
public expect fun McpManagedServerState.toSettingsState(): McpServerSettingsState

/**
 * Serializes immediate Settings writes in a scope that outlives popup disposal.
 *
 * Closing rejects new work and drains accepted writes while the consumer is alive.
 * A terminal consumer cancellation is not a successful drain and rejects later admission.
 */
public expect class SettingsUpdateQueue(
    commandScope: CoroutineScope,
    defaultReportError: ((Throwable) -> Unit)? = null,
) {
    public var drained: Boolean
        private set

    public fun submit(
        reportError: ((Throwable) -> Unit)? = null,
        block: suspend () -> Unit,
    ): Boolean

    /**
     * [onClosed] is cleanup, invoked even after cancellation; its flag is true only on normal drain.
     */
    public fun close(onClosed: ((drained: Boolean) -> Unit)? = null)
}
