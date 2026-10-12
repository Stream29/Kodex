package io.github.stream29.kodex.app.sessiontitlesettings

import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Admission to the existing application queue, NOT saved-state/remote success. Accepted freezes
 * baseline and payload before return and drains after child close/owner cancellation. Rejected
 * changes nothing; render the application's generic failure acknowledgement, not remote details.
 */
public enum class SessionTitleWriteAdmission { Accepted, Rejected }

/**
 * Read-only independent sources (not an atomic settings/models transaction). defaultModel must be
 * injected from the canonical title-generator owner; no literal/default backend dependency here.
 * Each typed write captures its exact field baseline/payload synchronously into the shared queue,
 * follows existing conflict rules and merges unrelated fields. No title generation/renaming occurs.
 * operationFailure/reportFailure/dismissFailure bind the shared application reporting source/port;
 * reporting/ack are nonthrowing, sanitized, local and exclude cancellation.
 */
public interface SessionTitleSettingsDependencies {
    public val settings: StateFlow<SessionTitleSettings>
    public val models: StateFlow<List<ModelInfo>>
    public val defaultModel: OpenAiModelId
    public val operationFailure: StateFlow<Boolean>
    /** @throws Exception Unexpected admission failure. */
    public fun setEnabled(expected: Boolean, enabled: Boolean): SessionTitleWriteAdmission
    /** Null means canonical default, not a missing/invalid model.
     * @throws Exception Unexpected admission failure.
     */
    public fun setModel(expected: OpenAiModelId?, model: OpenAiModelId?): SessionTitleWriteAdmission
    /** @throws Exception Unexpected admission failure. */
    public fun setReasoningEffort(
        expected: ReasoningEffort, reasoningEffort: ReasoningEffort,
    ): SessionTitleWriteAdmission
    public fun reportFailure(failure: Throwable): Unit
    public fun dismissFailure(): Unit
}

/**
 * Render Title generation: Automatic session title, Title model, Title reasoning, in that order.
 * configuredModel=null means injected compiled default; effectiveModel is always nonnull.
 * modelOptions is catalog order plus effective model, first-occurrence distinct; unavailable current
 * models remain visible/selectable, catalog changes never write settings. reasoningOptions is the
 * fixed baseline menu, unrelated to model catalog support. Disabled only disables renderer controls
 * and displays the explanation; it retains settings and DOES NOT reject programmatic commands.
 * operationFailure projects the shared source, displayed here OR by host; closed renders nothing.
 */
public data class SessionTitleSettingsState(
    public val enabled: Boolean,
    public val configuredModel: OpenAiModelId?,
    public val effectiveModel: OpenAiModelId,
    public val modelOptions: List<OpenAiModelId>,
    public val reasoningEffort: ReasoningEffort,
    public val operationFailure: Boolean = false,
    public val closed: Boolean = false,
) {
    public val reasoningOptions: List<ReasoningEffort>
        get() = listOf(
            ReasoningEffort.None, ReasoningEffort.Minimal, ReasoningEffort.Low,
            ReasoningEffort.Medium, ReasoningEffort.High, ReasoningEffort.XHigh, ReasoningEffort.Max,
        )
}

/**
 * Dispatcher-confined synchronous commands, no optimistic settings publication. Each reads the
 * dependency's current target-field baseline, not a renderer snapshot. Rejection/unknown exceptions
 * report the shared generic failure once; cancellation propagates unchanged without reporting or
 * retry. Close/owner completion stops only observations, freezes state and rejects future commands;
 * accepted queue entries survive. Hide has no business draft to discard; menu/focus is renderer-only.
 */
public interface SessionTitleSettingsViewModel : AutoCloseable {
    public val state: StateFlow<SessionTitleSettingsState>
    /** @throws kotlinx.coroutines.CancellationException Admission was cancelled. */
    public fun setEnabled(enabled: Boolean): Unit
    /** null restores canonical default; any nonnull model is retained, even outside the catalog.
     * @throws kotlinx.coroutines.CancellationException Admission was cancelled.
     */
    public fun setModel(model: OpenAiModelId?): Unit
    /** No catalog filtering or enabled-state rejection.
     * @throws kotlinx.coroutines.CancellationException Admission was cancelled.
     */
    public fun setReasoningEffort(reasoningEffort: ReasoningEffort): Unit
    public fun hidePage(): Unit
    /** Acknowledge shared failure; no-op when closed. */
    public fun dismissFailure(): Unit
    public override fun close(): Unit
}

/** Pure source projection and child observation jobs only; defaultModel is never copied as a literal. */
public fun interface SessionTitleSettingsViewModelFactory {
    public fun create(
        dependencies: SessionTitleSettingsDependencies, ownerScope: CoroutineScope,
    ): SessionTitleSettingsViewModel
}
