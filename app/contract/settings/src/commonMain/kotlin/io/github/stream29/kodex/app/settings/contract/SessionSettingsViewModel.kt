package io.github.stream29.kodex.app.settings.contract

import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path

/** Editable settings shared by a materialized Agent and a virtual New Session draft. */
public data class SessionSettingsConfiguration(
    public val model: OpenAiModelId,
    public val workingDirectory: Path,
    public val reasoningEffort: ReasoningEffort,
    public val serviceTier: ServiceTier,
    public val requestUserInputMode: RequestUserInputMode,
)

/** Kind of fixed target captured when the Settings popup opened. */
public enum class SessionSettingsTargetKind {
    MaterializedSession,
    NewSessionDraft,
}

/** Source snapshot before model-catalog choices are joined by the child ViewModel. */
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

/** Availability of the fixed Session Settings target. */
public sealed interface SessionSettingsDataState {
    public data object Unavailable : SessionSettingsDataState

    public data class Available(
        public val snapshot: SessionSettingsSnapshot,
    ) : SessionSettingsDataState
}

/**
 * Infrastructure-facing fixed-target settings port.
 *
 * Implementations adapt an exact materialized Agent/root pair or one exact
 * virtual New Session. They must not resolve the application's active target
 * again while processing a delayed command.
 */
public interface SessionSettingsDataSource : AutoCloseable {
    public val state: StateFlow<SessionSettingsDataState>

    /**
     * Attempts to update the exact target at [expectedRevision].
     *
     * Returns `false` when that target is no longer writable at the expected revision.
     */
    public suspend fun tryUpdateConfiguration(
        expectedRevision: Long,
        configuration: SessionSettingsConfiguration,
    ): Boolean

    /**
     * Attempts to rename the exact target at [expectedRevision].
     *
     * Returns `false` when that target is no longer writable at the expected revision.
     */
    public suspend fun tryRenameSession(
        expectedRevision: Long,
        sessionName: String,
    ): Boolean

    override fun close(): Unit
}

/** Frontend-ready state for Settings > Session. */
public sealed interface SessionSettingsState {
    public data object Unavailable : SessionSettingsState

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

/** One exact directory-picker child owned by Session Settings. */
public class SessionWorkingDirectoryPicker(
    public val expectedRevision: Long,
    public val viewModel: DirectoryPickerViewModel,
) {
    init {
        require(expectedRevision >= 0) {
            "A working-directory picker revision must not be negative."
        }
    }
}

/** One-shot frontend-local editor requested by the Session Settings child. */
public sealed interface SessionSettingsEffect {
    public data class RenameSession(
        public val expectedRevision: Long,
        public val initialName: String,
    ) : SessionSettingsEffect {
        init {
            require(expectedRevision >= 0) {
                "A rename request revision must not be negative."
            }
            require(initialName.isNotBlank()) {
                "A rename request must contain a non-blank current name."
            }
        }
    }
}

/** Settings > Session state owner bound to one exact target. */
public interface SessionSettingsViewModel : AutoCloseable {
    public val state: StateFlow<SessionSettingsState>
    public val directoryPicker: StateFlow<SessionWorkingDirectoryPicker?>
    public val effects: Flow<SessionSettingsEffect>

    public fun updateModel(expectedRevision: Long, model: OpenAiModelId): Unit
    public fun updateReasoningEffort(expectedRevision: Long, reasoningEffort: ReasoningEffort): Unit
    public fun updateServiceTier(expectedRevision: Long, serviceTier: ServiceTier): Unit
    public fun updateRequestUserInputMode(
        expectedRevision: Long,
        mode: RequestUserInputMode,
    ): Unit

    public fun requestWorkingDirectory(expectedRevision: Long): Unit

    /** Applies a directory only while [expected] is the current owned child. */
    public fun selectWorkingDirectory(
        expected: SessionWorkingDirectoryPicker,
        workingDirectory: Path,
    ): Boolean

    /** Closes [expected] only while it is the current owned child. */
    public fun dismissWorkingDirectoryPicker(
        expected: SessionWorkingDirectoryPicker,
    ): Boolean

    public fun requestRename(expectedRevision: Long): Unit
    public fun renameSession(expectedRevision: Long, sessionName: String): Unit

    override fun close(): Unit
}
