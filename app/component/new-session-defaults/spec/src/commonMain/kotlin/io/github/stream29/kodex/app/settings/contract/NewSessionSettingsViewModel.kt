package io.github.stream29.kodex.app.settings.contract

import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Canonical persisted defaults, not a current draft or existing Session's configuration.
 * Render the four fields in model/reasoning/tier/questions order. Choices preserve catalog order
 * plus the missing current model; reasoning uses None/Minimal/Low/Medium/High/XHigh/Max, tier and
 * questions use enum entries (not runtime capabilities). Stored custom effort remains displayable.
 * [revision] advances only when [settings] changes, never for catalog-only changes.
 * [active] false disables all fields/menus, retaining the last projection for passive display.
 *
 * @throws IllegalArgumentException if revision is negative, options repeat or omit settings.model.
 */
public data class NewSessionSettingsState(
    public val revision: Long,
    public val settings: KodexNewSessionSettings,
    public val modelOptions: List<OpenAiModelId>,
    public val active: Boolean = true,
) {
    init {
        require(revision >= 0) { "A New Session Settings revision must not be negative." }
        require(modelOptions.distinct().size == modelOptions.size) {
            "New Session Settings model options must be unique."
        }
        require(settings.model in modelOptions) { "The default New Session model must be selectable." }
    }
}

/** Synchronous queue admission, never a save/disk/CAS acknowledgement. */
public enum class NewSessionDefaultsAdmission { Accepted, Rejected }

/**
 * Typed field-admission ports, bound by the host to the original independent defaults queue.
 * Each admission freezes its `expected` field baseline and requested value before returning; accepted work survives
 * component close. Adapter CAS merges only that field with latest backend settings and preserves
 * title/auth/context/MCP and other fields. No optimistic persisted projection.
 * Commands and observation are confined to the owner's interaction dispatcher.
 *
 * Each admission may return Rejected for expired ownership/queue/field baseline. Later stale CAS
 * rejection is not an exception. Later ordinary failures/success are reported/cleared by the adapter
 * in the existing shared failure authority; cancellation is propagated, not reported as failure.
 * The component never injects/stores full BackendSettings, RPC or a global settings store.
 */
public interface NewSessionDefaultsDependencies {
    /** Borrowed complete canonical defaults; no locally copied model defaults. */
    public val defaults: StateFlow<KodexNewSessionSettings>
    /** Borrowed read-only catalog; preserves ordering. */
    public val models: StateFlow<List<ModelInfo>>
    /** Existing application-level failure authority, not a component-local error store. */
    public val operationFailure: StateFlow<Boolean>

    /**
     * Freezes the model field baseline and admits into the host queue.
     * @throws CancellationException if synchronous admission is cancelled.
     * @throws Exception if synchronous admission fails.
     */
    public fun admitModel(expected: OpenAiModelId, requested: OpenAiModelId): NewSessionDefaultsAdmission
    /**
     * Freezes the effort field baseline; fixed UI choices do not constrain programmatic stored values.
     * @throws CancellationException if synchronous admission is cancelled.
     * @throws Exception if synchronous admission fails.
     */
    public fun admitReasoningEffort(expected: ReasoningEffort, requested: ReasoningEffort): NewSessionDefaultsAdmission
    /**
     * Freezes the tier field baseline.
     * @throws CancellationException if synchronous admission is cancelled.
     * @throws Exception if synchronous admission fails.
     */
    public fun admitServiceTier(expected: ServiceTier, requested: ServiceTier): NewSessionDefaultsAdmission
    /**
     * Freezes the questions field baseline.
     * @throws CancellationException if synchronous admission is cancelled.
     * @throws Exception if synchronous admission fails.
     */
    public fun admitRequestUserInputMode(
        expected: RequestUserInputMode,
        requested: RequestUserInputMode,
    ): NewSessionDefaultsAdmission
    /** Records synchronous non-cancellation failure in shared authority; must not throw. */
    public fun reportFailure(failure: Throwable): Unit
    /** Acknowledges the existing shared failure; must not throw. */
    public fun dismissFailure(): Unit
}

/** Creates a stable defaults child. Owner cancellation releases observations, not accepted writes. */
public fun interface NewSessionDefaultsViewModelFactory {
    /** Synchronously projects borrowed sources, then observes them in a supervised child scope. */
    public fun create(
        dependencies: NewSessionDefaultsDependencies,
        ownerScope: CoroutineScope,
    ): NewSessionSettingsViewModel
}

/**
 * Defaults state owner. All four legacy Unit commands check active/current revision before invoking
 * their typed field port with a frozen field baseline; stale/closed commands are no-ops.
 * Accepted/rejected admission does not mutate projection or revision. Ordinary synchronous failures
 * are reported once through dependencies; asynchronous outcomes belong to the host queue.
 * Hiding only disposes renderer menus; it does not stop observations or edit persisted defaults.
 * Title is an independent sibling component, not a fifth defaults field.
 */
public interface NewSessionSettingsViewModel : AutoCloseable {
    public val state: StateFlow<NewSessionSettingsState>
    /** Borrowed failure projection; host should render it once or disable this panel's banner. */
    public val operationFailure: StateFlow<Boolean>

    /**
     * Admits a model change against the current field baseline.
     * @throws CancellationException if synchronous admission is cancelled.
     */
    public fun updateModel(expectedRevision: Long, model: OpenAiModelId): Unit
    /**
     * Admits an effort change against the current field baseline.
     * @throws CancellationException if synchronous admission is cancelled.
     */
    public fun updateReasoningEffort(expectedRevision: Long, reasoningEffort: ReasoningEffort): Unit
    /**
     * Admits a tier change against the current field baseline.
     * @throws CancellationException if synchronous admission is cancelled.
     */
    public fun updateServiceTier(expectedRevision: Long, serviceTier: ServiceTier): Unit
    /**
     * Admits a questions change against the current field baseline.
     * @throws CancellationException if synchronous admission is cancelled.
     */
    public fun updateRequestUserInputMode(expectedRevision: Long, mode: RequestUserInputMode): Unit
    /** Acknowledges shared failure while active; closed child does nothing. */
    public fun dismissOperationFailure(): Unit

    /**
     * Idempotently rejects new edits and releases child observations; does not close borrowed sources,
     * cancel/close the host queue or retract accepted commands. The host closes/drains the original
     * defaults queue after child close. Latest state remains passively displayable with active=false.
     */
    override fun close(): Unit
}
