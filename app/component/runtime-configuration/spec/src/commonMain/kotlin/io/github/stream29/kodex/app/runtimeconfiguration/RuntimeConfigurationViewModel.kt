package io.github.stream29.kodex.app.runtimeconfiguration

import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** Four runtime-editable values, not a complete settings snapshot or a writable settings store. */
public data class RuntimeConfiguration(
    public val model: OpenAiModelId,
    public val reasoning: ReasoningEffort,
    public val tier: ServiceTier,
    public val requestUserInputMode: RequestUserInputMode,
)

/**
 * Read-only inputs and writes bound to ONE exact Agent binding or New Session draft.
 *
 * [configuration] must read through that owner's current settings, never a child-maintained copy.
 * [models] preserves catalog order. Neither observation transfers ownership of either source.
 * The adapter must reject an invalidated target using its existing identity/revision safeguards;
 * it must never look up the currently selected owner to redirect a delayed command.
 *
 * Writes transform the owner's latest complete settings, preserving cwd, thread and all unrelated
 * fields. Model configuration is one atomic three-field update, not three field commands/CAS calls.
 * Mode is a separate field update. These suspend operations run in the caller, not a durable
 * application/owner queue. An RPC already accepted remotely need not be rolled back on cancellation.
 * Failure reporting remains with the existing host/adapter; ports do not expose raw RPC or stores.
 */
public interface RuntimeConfigurationDependencies {
    public val configuration: StateFlow<RuntimeConfiguration>
    public val models: StateFlow<List<ModelInfo>>

    /**
     * Updates the explicit tuple once, without capability correction or a new running-state lock.
     * Returns when the existing owner command completes; does not imply an additional persistence
     * transaction beyond that command.
     * @throws IllegalStateException The bound owner is stale, closed or unavailable.
     * @throws kotlinx.coroutines.CancellationException The caller or target wait was cancelled.
     * @throws Exception The bound owner's update failed; propagate the existing failure unchanged.
     */
    public suspend fun updateModelConfiguration(
        model: OpenAiModelId,
        effort: ReasoningEffort,
        tier: ServiceTier,
    ): Unit

    /**
     * Updates only questions mode on the bound owner's latest settings.
     * @throws IllegalStateException The bound owner is stale, closed or unavailable.
     * @throws kotlinx.coroutines.CancellationException The caller or target wait was cancelled.
     * @throws Exception The bound owner's update failed; propagate the existing failure unchanged.
     */
    public suspend fun updateRequestUserInputMode(mode: RequestUserInputMode): Unit
}

/**
 * One model's menu capabilities. Both lists are nonempty in ViewModel-produced state.
 * Efforts retain the first matching ModelInfo's advertised order (including custom values); when
 * absent/empty they contain only the CURRENT configuration's effort. Tiers use the canonical
 * ModelInfo.availableServiceTiers order, always including Default; missing metadata gives Default.
 * Capability choices never correct the saved configuration, even when it is not among them.
 */
public data class RuntimeConfigurationModelOption(
    public val model: OpenAiModelId,
    public val efforts: List<ReasoningEffort>,
    public val tiers: List<ServiceTier>,
)

/**
 * Pure projection of configuration and catalog, plus local lifecycle.
 *
 * [modelOptions] is catalog slugs followed by the current model, distinct in first-occurrence order.
 * Duplicate catalog slugs use the FIRST entry's capabilities. Independently emitted configuration
 * and catalog values are not promised an atomic source transaction. No optimistic edits, loading,
 * business failure copy, running lock, full Agent, RPC or global settings appear in this state.
 *
 * Active rendering shows two triggers: model + effort + non-Default tier, then questions mode.
 * The model menu has model -> effort -> tier levels and initially focuses the current model.
 * Model/effort only navigate; a tier leaf sends ONE explicit tuple.
 * Initial effort focus is the saved effort for the current model if available,
 * otherwise the first effort; initial tier focus is the saved tier for the current model/effort if
 * available, otherwise Default. Questions uses enum order and sends only mode. Focus, anchors and
 * open menus belong to the renderer; Escape dismisses a menu level without any write.
 * [closed] freezes the last projection and renders nothing, including no host-level menus.
 */
public data class RuntimeConfigurationState(
    public val configuration: RuntimeConfiguration,
    public val modelOptions: List<RuntimeConfigurationModelOption>,
    public val closed: Boolean = false,
)

/**
 * Stable child of one exact settings owner. Commands/close are confined to the owner's dispatcher;
 * observations are collected there. Commands retain their supplied values across suspension/catalog changes;
 * they never resolve a replacement active owner or revalidate against a newer menu capability list.
 *
 * Every call independently invokes its one port once; no retry, serialization queue, optimistic
 * projection, automatic correction or cancellation-as-business-failure is added. Concurrent calls
 * retain the bound owner's existing ordering/CAS policy, not a new component ordering authority.
 * Caller cancellation and component close cancel local waits. Backend acceptance is not rolled back.
 */
public interface RuntimeConfigurationViewModel : AutoCloseable {
    public val state: StateFlow<RuntimeConfigurationState>

    /**
     * Submits the model/effort/tier leaf tuple once; model/effort navigation does not call this.
     * After close, returns without invoking the port.
     * @throws IllegalStateException The bound owner rejects a stale, closed or unavailable target.
     * @throws kotlinx.coroutines.CancellationException An active call's caller or local wait is cancelled.
     * @throws Exception The dependency update fails; propagated unchanged without retry/reporting.
     */
    public suspend fun updateModelConfiguration(
        model: OpenAiModelId,
        effort: ReasoningEffort,
        tier: ServiceTier,
    ): Unit

    /**
     * Submits mode alone once. After close, returns without invoking the port.
     * @throws IllegalStateException The bound owner rejects a stale, closed or unavailable target.
     * @throws kotlinx.coroutines.CancellationException An active call's caller or local wait is cancelled.
     * @throws Exception The dependency update fails; propagated unchanged without retry/reporting.
     */
    public suspend fun updateRequestUserInputMode(mode: RequestUserInputMode): Unit

    /**
     * Idempotently freezes projection, stops local observation and cancels local command waits.
     * Does not close borrowed ports, Agent/Draft/binding or parent scope, and does not undo accepted
     * backend work. Owner-scope completion has the same effect. Hiding menus/unmounting a renderer
     * does not close this child; the renderer composition scope owns its suspend-call waits.
     * Replacing the exact rendered child disposes those old waits and menu/focus handles without
     * closing the borrowed child or redirecting its commands to the replacement.
     */
    public override fun close(): Unit
}

/** Creates local observation only: no writes, source close, store, active-owner lookup or I/O. */
public fun interface RuntimeConfigurationViewModelFactory {
    public fun create(
        dependencies: RuntimeConfigurationDependencies,
        ownerScope: CoroutineScope,
    ): RuntimeConfigurationViewModel
}
