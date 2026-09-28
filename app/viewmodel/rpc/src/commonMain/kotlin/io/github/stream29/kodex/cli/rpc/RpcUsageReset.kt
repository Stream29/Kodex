package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.app.settings.contract.UsageResetOption
import io.github.stream29.kodex.app.settings.contract.UsageResetRequest
import io.github.stream29.kodex.app.settings.contract.UsageResetState
import io.github.stream29.kodex.app.settings.contract.snapshotOrNull
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Local selection/confirmation only. No account credentials, reset attempt, or default credit. */
public class RpcUsageReset(
    private val rpc: GlobalRpc,
    private val usage: StateFlow<SettingsAccountUsageState>,
    scope: CoroutineScope,
) : AutoCloseable {
    private val owner = Job(scope.coroutineContext[Job])
    private val local = CoroutineScope(scope.coroutineContext + owner)
    private val mutable = MutableStateFlow<UsageResetState>(UsageResetState.Hidden)
    override fun close() { owner.cancel(); mutable.value = UsageResetState.Hidden }
    public val state: StateFlow<UsageResetState> = mutable.asStateFlow()
    private var consuming: Job? = null

    public fun show() {
        if (!owner.isActive || consuming?.isActive == true) return
        val credits = usage.value.snapshotOrNull()?.resetCredits
        val options = credits?.credits.orEmpty().map { credit ->
            UsageResetOption(
                credit.id, credit.title ?: "Full reset",
                credit.description ?: "Reset current usage limits.", credit.expiresAt,
            )
        }
        mutable.value = if (options.isEmpty()) UsageResetState.PreparationFailed
        else UsageResetState.Choosing(UsageResetRequest(
            (credits?.availableCount ?: options.size.toLong()).coerceAtLeast(options.size.toLong()), options,
        ))
    }

    public fun select(creditId: String) {
        if (!owner.isActive) return
        val choosing = mutable.value as? UsageResetState.Choosing ?: return
        val option = choosing.request.options.singleOrNull { it.creditId == creditId } ?: return
        mutable.value = UsageResetState.Confirming(option)
    }

    /** An exact confirmation object prevents delayed UI actions from consuming another choice. */
    public fun confirm(expected: UsageResetState.Confirming) {
        if (!owner.isActive || mutable.value !== expected || consuming?.isActive == true) return
        val id = requireNotNull(expected.option.creditId)
        mutable.value = UsageResetState.Consuming(expected.option)
        consuming = local.launch {
            try {
                val outcome = rpc.consumeUsageReset(id)
                mutable.value = UsageResetState.Completed(outcome, selectedCredit = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Refresh is not evidence that an unknown-result command failed to commit.
                mutable.value = UsageResetState.ConsumeFailed(expected.option)
                try { rpc.refreshAccountUsage() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Throwable) { /* Preserve the explicit failure; no automatic retry. */ }
            }
        }
    }

    public fun dismiss() {
        if (consuming?.isActive != true) mutable.value = UsageResetState.Hidden
    }
}
