package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.rpc.client.FrontendSettingsAccess
import io.github.stream29.kodex.rpc.client.rpcStateIn
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.models.CliFrontendSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow

/** Read projection plus captured-field CAS editing; no filesystem API or offline write queue. */
internal class RpcFrontendSettings private constructor(
    private val rpc: GlobalRpc,
    override val settings: StateFlow<CliFrontendSettings>,
) : FrontendSettingsAccess {
    override suspend fun update(transform: (CliFrontendSettings) -> CliFrontendSettings): CliFrontendSettings {
        val baseline = settings.value
        val desired = transform(baseline)
        if (baseline == desired) return baseline
        while (true) {
            currentCoroutineContext().ensureActive()
            val latest = settings.value
            check(baseline.newLineKey == desired.newLineKey || latest.newLineKey == baseline.newLineKey) { "Frontend preference changed." }
            check(baseline.sidebars == desired.sidebars || latest.sidebars == baseline.sidebars) { "Sidebar preference changed." }
            check(baseline.hooks == desired.hooks || latest.hooks == baseline.hooks) { "Frontend Hooks changed." }
            val proposal = latest.copy(
                newLineKey = if (baseline.newLineKey != desired.newLineKey) desired.newLineKey else latest.newLineKey,
                sidebars = if (baseline.sidebars != desired.sidebars) desired.sidebars else latest.sidebars,
                hooks = if (baseline.hooks != desired.hooks) desired.hooks else latest.hooks,
            )
            if (rpc.compareAndSetFrontendSettings(latest, proposal)) return proposal
            delay(50)
        }
    }
    companion object {
        suspend fun open(rpc: GlobalRpc, scope: CoroutineScope): RpcFrontendSettings =
            RpcFrontendSettings(rpc, scope.rpcStateIn(rpc::getFrontendSettings, rpc::getFrontendSettingsFlow))
    }
}
