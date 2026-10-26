@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.app.application.contract.SidebarSettingsViewModel
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.rpc.local.BackendConnectionState
import kotlinx.coroutines.flow.StateFlow

/** Only local frontend presentation is passed to the renderer. */
public expect class KodexApplication(
    viewModel: ApplicationViewModel,
    newLineKey: StateFlow<NewLineKey>,
    sidebarSettings: SidebarSettingsViewModel,
    reportUnhandledError: (Throwable) -> Unit,
    reportWarning: (String) -> Unit,
    connectionState: StateFlow<BackendConnectionState>,
    connectionFailureReason: StateFlow<String?>,
) {
    public val viewModel: ApplicationViewModel
    public val newLineKey: StateFlow<NewLineKey>
    public val sidebarSettings: SidebarSettingsViewModel
    /**
     * The actual frontend host outlet for unhandled non-cancellation operations.
     * Logs the original failure and offers one observation to this Application's
     * host diagnostics consumer (local Hooks on Native). Return is not consumer completion.
     * Delivery is live and best-effort, with no replay and 64 buffered messages;
     * a slow consumer drops the oldest buffered observation.
     * Cancellation is not reported. Neither a backend Agent Stop nor a new RPC call
     * is produced; closing the Application cancels pending local observations.
     */
    public val reportUnhandledError: (Throwable) -> Unit
    /**
     * Reports an actual frontend warning through the same host diagnostics consumer.
     * Native uses the same Error Hook selection and
     * `{"type":"unhandled_error","message":...}` input as [reportUnhandledError].
     * Logs at warning severity, without creating an inline notice or a backend Stop.
     * Shares the same live, best-effort buffer and single consumer.
     */
    public val reportWarning: (String) -> Unit
    public val connectionState: StateFlow<BackendConnectionState>
    public val connectionFailureReason: StateFlow<String?>
}
