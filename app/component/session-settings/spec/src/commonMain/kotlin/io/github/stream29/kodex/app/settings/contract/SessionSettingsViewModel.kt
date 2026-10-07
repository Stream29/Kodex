package io.github.stream29.kodex.app.settings.contract

import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameViewModel
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryViewModel
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path

/** Editable fields shared by one materialized Session and one virtual New Session draft. */
public data class SessionSettingsConfiguration(
    public val model: OpenAiModelId,
    public val workingDirectory: Path,
    public val reasoningEffort: ReasoningEffort,
    public val serviceTier: ServiceTier,
    public val requestUserInputMode: RequestUserInputMode,
)

/** Kind of the fixed target captured at creation; never a lookup of the current selected tab. */
public enum class SessionSettingsTargetKind { MaterializedSession, NewSessionDraft }

/**
 * Source identity/configuration projection, before joining the model catalog.
 * [revision] is a local exact-target revision, not a backend transaction id.
 * Source-observable identity/configuration/editability changes invalidate it;
 * catalog-only changes do not. Revisions must not be reused for a replacement target.
 *
 * @throws IllegalArgumentException if [revision] is negative or [sessionName] is blank.
 */
public data class SessionSettingsSnapshot(
    public val revision: Long,
    public val targetKind: SessionSettingsTargetKind,
    public val sessionName: String,
    public val configuration: SessionSettingsConfiguration,
    public val editable: Boolean,
) {
    init {
        require(revision >= 0) { "A Session Settings revision must not be negative." }
        require(sessionName.isNotBlank()) { "A Session Settings name must not be blank." }
    }
}

/** Exact target availability. Unavailable is not an editable empty/default Session. */
public sealed interface SessionSettingsDataState {
    public data object Unavailable : SessionSettingsDataState
    public data class Available(public val snapshot: SessionSettingsSnapshot) : SessionSettingsDataState
}

/**
 * Infrastructure port bound to one exact binding or draft, transferred to the component.
 * Never resolve the active tab again during delayed commands or CAS retries.
 * Source owns revision production, field-baseline merging and runtime-owned field preservation.
 * A reactivated materialized Session does not revive this source.
 */
public interface SessionSettingsDataSource : AutoCloseable {
    /** Current availability; observation itself does not initiate an edit. */
    public val state: StateFlow<SessionSettingsDataState>

    /**
     * Attempts the frozen [configuration] at [expectedRevision].
     * Return false for unavailable, stale or nonwritable targets/field-baseline conflicts.
     * Only changed fields may replace latest values; unrelated/runtime-owned values survive.
     * True is the source's operation outcome, not a universal disk durability guarantee.
     *
     * @throws CancellationException if the caller/source/binding expires while waiting or retrying.
     * @throws Exception if the underlying read/CAS/update fails.
     */
    public suspend fun tryUpdateConfiguration(
        expectedRevision: Long,
        configuration: SessionSettingsConfiguration,
    ): Boolean

    /**
     * Attempts the already trimmed, nonblank name at the frozen revision.
     * Rename admission is revision-based, not gated by configuration [SessionSettingsSnapshot.editable].
     * Return false for a stale/unavailable target or conflicting name baseline.
     *
     * @throws IllegalArgumentException if [sessionName] is blank.
     * @throws CancellationException if the caller/source/binding expires.
     * @throws Exception if the underlying operation fails.
     */
    public suspend fun tryRenameSession(expectedRevision: Long, sessionName: String): Boolean

    /**
     * Idempotently releases only this binding/observation, never the Session, draft or catalog.
     * Implementations must not throw during cleanup.
     */
    override fun close(): Unit
}

/**
 * Render Unavailable as "No selected session", with no identity/configuration controls.
 * Available renders Identity (name, Rename, cwd, Browse) and Model behavior (four fields).
 * Editable controls configuration/Browse only; Rename remains revision-admissible.
 */
public sealed interface SessionSettingsState {
    public data object Unavailable : SessionSettingsState

    /**
     * [modelOptions] preserves catalog order then appends the current model if absent, deduplicated.
     * Display stored effort/tier/mode without normalization. Reasoning choices are the fixed known
     * None/Minimal/Low/Medium/High/XHigh/Max list, tier and questions choices are their enum entries;
     * these are deliberately not runtime model-capability menus.
     *
     * @throws IllegalArgumentException if options repeat or omit the current model.
     */
    public data class Available(
        public val snapshot: SessionSettingsSnapshot,
        public val modelOptions: List<OpenAiModelId>,
    ) : SessionSettingsState {
        init {
            require(modelOptions.distinct().size == modelOptions.size) {
                "Session Settings model options must be unique."
            }
            require(snapshot.configuration.model in modelOptions) {
                "The current Session model must be selectable."
            }
        }
    }
}

/**
 * Reference-identity selection handle owned by the parent, bound at open time.
 * Render [selection] directly; do not mirror its browser/draft.
 *
 * @throws IllegalArgumentException if [expectedRevision] is negative.
 */
public class SessionWorkingDirectoryPicker(
    public val expectedRevision: Long,
    public val selection: WorkingDirectoryViewModel,
) {
    init { require(expectedRevision >= 0) { "A working-directory picker revision must not be negative." } }
}

/**
 * Reference-identity rename handle. The exact child owns draft/validation, the parent owns target
 * and dismissal. Render with SessionRenamePopup's Labeled presentation; disposal closes this child.
 * Submission normally returns after revision-checked queue admission, not persistence.
 *
 * @throws IllegalArgumentException if [expectedRevision] is negative.
 */
public class SessionSettingsRename(
    public val expectedRevision: Long,
    public val viewModel: SessionRenameViewModel,
) {
    init { require(expectedRevision >= 0) { "A rename request revision must not be negative." } }
}

/**
 * Narrow dependencies, not a full RPC/global store. Factory takes ownership of [source] only.
 * [models] is borrowed. Directory factory creates a fresh owned browser or returns null without
 * replacing an existing child. Reporter receives the cwd frozen at command admission, never a
 * newly selected Session's cwd. A null reporter preserves the legacy generic local logging fallback,
 * without logging a settings payload. All interactions are confined to the owner's dispatcher.
 */
public data class SessionSettingsDependencies(
    public val source: SessionSettingsDataSource,
    public val models: StateFlow<List<ModelInfo>>,
    public val createDirectoryPicker: (Path) -> DirectoryPickerViewModel? = { null },
    public val reportUnhandledError: ((Throwable, Path) -> Unit)? = null,
)

/** Dependency-only creation seam; owner cancellation has the same cleanup policy as close. */
public fun interface SessionSettingsViewModelFactory {
    /**
     * Creates one stable child with a supervised observation/serial command lifetime.
     * Initial state is projected synchronously; mounting a renderer does not acquire another source.
     * Cancelling [ownerScope] closes children/source and cancels queued CAS/retries, never drains.
     */
    public fun create(
        dependencies: SessionSettingsDependencies,
        ownerScope: CoroutineScope,
    ): SessionSettingsViewModel
}

/**
 * Settings > Session owner, bound to one exact target.
 * Commands are dispatcher-confined and non-suspending admission; closed/stale/unavailable commands
 * do nothing. Frozen revision/configuration/name/cwd enter one FIFO local queue. Source rejection
 * does not imply failure; ordinary asynchronous failures are sent once to the frozen reporter.
 * Cancellation is not reported and stops local pending work, releasing source/children and publishing
 * Unavailable. No optimistic persisted state.
 * Synchronous browser creation failures propagate. Injected reporters must not throw.
 */
public interface SessionSettingsViewModel : AutoCloseable {
    public val state: StateFlow<SessionSettingsState>
    public val directoryPicker: StateFlow<SessionWorkingDirectoryPicker?>
    public val rename: StateFlow<SessionSettingsRename?>
    /** Admits only the current editable revision; no model capability filtering or auto-correction. */
    public fun updateModel(expectedRevision: Long, model: OpenAiModelId): Unit
    /** Same admission as updateModel; custom stored values remain displayable. */
    public fun updateReasoningEffort(expectedRevision: Long, reasoningEffort: ReasoningEffort): Unit
    /** Same admission as updateModel. */
    public fun updateServiceTier(expectedRevision: Long, serviceTier: ServiceTier): Unit
    /** Same admission as updateModel; changes only the questions field. */
    public fun updateRequestUserInputMode(expectedRevision: Long, mode: RequestUserInputMode): Unit

    /**
     * Opens for the current editable revision; null factory result leaves existing children alone.
     * Successful opening closes any previous directory/rename child.
     *
     * @throws CancellationException if the injected browser factory is synchronously cancelled.
     * @throws Exception if the injected browser factory fails.
     */
    public fun requestWorkingDirectory(expectedRevision: Long): Unit

    /**
     * Consumes/closes only the exact current [expected] handle, then attempts queue admission.
     * True means handle consumed, NOT persisted or even accepted for writing: a stale, unavailable
     * or noneditable revision may still cause no write. False leaves a replacement untouched.
     */
    public fun selectWorkingDirectory(expected: SessionWorkingDirectoryPicker, workingDirectory: Path): Boolean
    /** Consumes/closes only the current exact handle without writing. */
    public fun dismissWorkingDirectoryPicker(expected: SessionWorkingDirectoryPicker): Boolean

    /** Opens an exact rename child for current revision even when configuration is noneditable. */
    public fun requestRename(expectedRevision: Long): Unit
    /** Closes only the exact current rename child; stale completion cannot dismiss a replacement. */
    public fun dismissRename(expected: SessionSettingsRename): Boolean
    /** Trims nonblank input and queues against current revision, without an editable-global lock. */
    public fun renameSession(expectedRevision: Long, sessionName: String): Unit

    /**
     * Disposes unaccepted directory/rename children only, keeping source/catalog observations alive.
     * Already admitted commands keep running. Renderer removal/page navigation invokes this, not close.
     */
    public fun hidePage(): Unit

    /**
     * Idempotently rejects edits, disposes all children, closes source, stops observations and
     * cancels pending CAS/retries (NOT drain). State becomes Unavailable. Does not close models or the
     * owner scope, and cannot promise rollback of operations already accepted by the backend.
     */
    override fun close(): Unit
}
