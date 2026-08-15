package io.github.stream29.kodex.hook.contract

import kotlinx.coroutines.flow.StateFlow

/** Environment input used only by a manager command and never published as a value. */
public sealed interface HookEnvironmentDraft {
    /** Retains the existing value while editing. Invalid for a new name. */
    public data object Keep : HookEnvironmentDraft

    /** Replaces the value with the supplied contents. */
    public data class Replace(
        public val value: String,
    ) : HookEnvironmentDraft
}

/** Validated manager input for one complete Hook source. */
public data class HookSourceDraft(
    public val name: String,
    public val enabled: Boolean = true,
    public val environment: Map<String, HookEnvironmentDraft> = emptyMap(),
    public val hooks: HookDeclarations,
)

/** Import provenance safe for Settings presentation. */
public data class HookCodexImportSummary(
    public val sourceKind: HookCodexSourceKind,
    public val normalizedPath: String,
)

/** Complete sanitized state for one Kodex-owned Hook source. */
public data class HookManagedSourceState(
    public val sourceId: String,
    public val name: String,
    public val enabled: Boolean,
    public val importedFrom: HookCodexImportSummary?,
    public val configuredEvents: List<HookEvent>,
    public val commandCount: Int,
    public val environmentNames: List<String>,
) {
    init {
        require(sourceId.isNotBlank()) { "A managed Hook source id must not be blank." }
        require(name.isNotBlank()) { "A managed Hook source name must not be blank." }
        require(commandCount >= 0) { "A managed Hook command count must not be negative." }
        require(environmentNames == environmentNames.distinct().sorted()) {
            "Managed Hook environment names must be unique and sorted."
        }
    }
}

/** Complete source contents decoded during one explicit Codex import. */
public data class HookCodexImportTemplate(
    public val name: String,
    public val enabled: Boolean = true,
    public val environment: Map<String, HookEnvironmentValue> = emptyMap(),
    public val hooks: HookDeclarations,
) {
    init {
        require(name.isNotBlank()) { "An imported Hook source name must not be blank." }
        require(environment.keys.all(String::isNotBlank)) {
            "Imported Hook environment names must not be blank."
        }
    }
}

/** One Codex configuration file classified for an explicit import preview. */
public sealed interface HookCodexImportCandidate {
    public val identity: HookCodexImportIdentity
    public val displayName: String

    /**
     * A source with at least one supported command Hook.
     *
     * [excludedDetails] contains field or feature names only, never command or
     * environment values. A non-empty list classifies this source as partial.
     */
    public data class Supported(
        override val identity: HookCodexImportIdentity,
        override val displayName: String,
        public val template: HookCodexImportTemplate,
        public val excludedDetails: List<String> = emptyList(),
    ) : HookCodexImportCandidate {
        init {
            require(displayName.isNotBlank()) {
                "A Codex Hook import display name must not be blank."
            }
            require(template.hooks.commandCount > 0) {
                "A supported Codex Hook import must contain a command."
            }
            require(excludedDetails.all(String::isNotBlank)) {
                "Excluded Codex Hook details must not be blank."
            }
        }
    }

    /** A source with no command Hook Kodex can preserve. */
    public data class Unsupported(
        override val identity: HookCodexImportIdentity,
        override val displayName: String,
        public val detail: String,
    ) : HookCodexImportCandidate {
        init {
            require(displayName.isNotBlank()) {
                "A Codex Hook import display name must not be blank."
            }
            require(detail.isNotBlank()) {
                "An unsupported Codex Hook import detail must not be blank."
            }
        }
    }
}

/** Explicit, one-shot Codex Hook import source. */
public fun interface HookCodexImportSource {
    public suspend fun read(): List<HookCodexImportCandidate>
}

/** Whether a preview source is new or replaces the same prior Codex source. */
public enum class HookImportDisposition {
    New,
    Conflict,
}

/** Degree to which Kodex can preserve one Codex Hook source. */
public enum class HookImportSupport {
    Full,
    Partial,
    Unsupported,
}

/** One content-free Codex Hook import preview row. */
public data class HookImportItem(
    public val sourceKey: String,
    public val displayName: String,
    public val sourceKind: HookCodexSourceKind,
    public val normalizedPath: String,
    public val disposition: HookImportDisposition?,
    public val support: HookImportSupport,
    public val configuredEvents: List<HookEvent>,
    public val commandCount: Int,
    public val environmentNames: List<String>,
    public val excludedDetails: List<String> = emptyList(),
    public val selectable: Boolean,
) {
    init {
        require(sourceKey.isNotBlank()) { "A Hook import source key must not be blank." }
        require(displayName.isNotBlank()) { "A Hook import display name must not be blank." }
        require(normalizedPath.isNotBlank()) { "A Hook import path must not be blank." }
        require(commandCount >= 0) { "A Hook import command count must not be negative." }
        require(environmentNames == environmentNames.distinct().sorted()) {
            "Hook import environment names must be unique and sorted."
        }
        require(excludedDetails.all(String::isNotBlank)) {
            "Hook import exclusion details must not be blank."
        }
        require(selectable == (support != HookImportSupport.Unsupported)) {
            "Only supported or partially supported Hook imports are selectable."
        }
        require((support == HookImportSupport.Unsupported) == (disposition == null)) {
            "Unsupported Hook imports must not have a new/conflict disposition."
        }
    }
}

/** Immutable preview token and its filtered, sanitized entries. */
public data class HookImportPreview(
    public val id: Long,
    public val filter: String,
    public val items: List<HookImportItem>,
) {
    init {
        require(id > 0) { "A Hook import preview id must be positive." }
    }
}

/** Explicit action for one source during the atomic import commit. */
public enum class HookImportDecision {
    Skip,
    Import,
    Replace,
}

/** Atomic persistence port over the Hook portion of global settings. */
public interface HookConfigurationStore {
    public val configuration: StateFlow<HookConfiguration>

    public suspend fun update(
        transform: (HookConfiguration) -> HookConfiguration,
    ): HookConfiguration
}

/** Generates a stable Kodex-owned id when a source is first added or imported. */
public fun interface HookSourceIdFactory {
    public fun create(): String
}

/**
 * Application-wide Hook management and sanitized-state authority.
 *
 * Source edits replace one complete source while preserving its stable id and
 * optional Codex import identity.
 */
public interface HookManager : AutoCloseable {
    public val featureEnabled: StateFlow<Boolean>
    public val sources: StateFlow<List<HookManagedSourceState>>

    public suspend fun setFeatureEnabled(enabled: Boolean): Unit
    public suspend fun add(draft: HookSourceDraft): String
    public suspend fun edit(sourceId: String, draft: HookSourceDraft): Unit
    public suspend fun delete(sourceId: String): Unit
    public suspend fun setEnabled(sourceId: String, enabled: Boolean): Unit

    /**
     * Returns an editor draft with environment values represented only by
     * [HookEnvironmentDraft.Keep].
     */
    public fun editorDraft(sourceId: String): HookSourceDraft?

    public suspend fun previewCodexImport(filter: String = ""): HookImportPreview
    public suspend fun applyCodexImport(
        previewId: Long,
        decisions: Map<String, HookImportDecision>,
    ): Unit

    override fun close(): Unit
}
