package io.github.stream29.codex.lite.openai.codexclistorage

import kotlinx.io.files.Path
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Provenance of one Codex Hook configuration layer. */
@Serializable
public enum class CodexCliHookSourceKind {
    @SerialName("system")
    System,

    @SerialName("user")
    User,

    @SerialName("project")
    Project,

    @SerialName("session")
    Session,
}

/**
 * Compiled matcher from one Codex Hook matcher group.
 *
 * The serializer reads the configured string directly. Omitted matchers use
 * [All], simple `a|b` matchers become [Exact], and other valid patterns become
 * [RegularExpression].
 */
@Serializable(with = CodexCliHookMatcherSerializer::class)
public sealed interface CodexCliHookMatcher {
    /** Original configured pattern, normalized to `*` for [All]. */
    public val pattern: String

    public fun matches(inputs: List<String>): Boolean

    public data object All : CodexCliHookMatcher {
        override val pattern: String = "*"

        override fun matches(inputs: List<String>): Boolean = true
    }

    public data class Exact(
        override val pattern: String,
    ) : CodexCliHookMatcher {
        @Transient
        private val candidates: Set<String> = pattern.split('|').toSet()

        init {
            require(pattern.isExactCodexHookMatcher()) {
                "Exact Codex Hook matcher contains regular-expression syntax: $pattern"
            }
        }

        override fun matches(inputs: List<String>): Boolean = inputs.any(candidates::contains)
    }

    public data class RegularExpression(
        override val pattern: String,
    ) : CodexCliHookMatcher {
        @Transient
        private val regex: Regex = Regex(pattern)

        override fun matches(inputs: List<String>): Boolean = inputs.any(regex::containsMatchIn)
    }

    /**
     * Preserves an invalid configured regular expression without making the
     * complete Hook source unreadable. Invalid matchers never select an input.
     */
    public data class Invalid(
        override val pattern: String,
    ) : CodexCliHookMatcher {
        override fun matches(inputs: List<String>): Boolean = false
    }

    public companion object {
        /** Parses and compiles one configured matcher. */
        public fun parse(pattern: String): CodexCliHookMatcher = when {
            pattern.isEmpty() || pattern == "*" -> All
            pattern.isExactCodexHookMatcher() -> Exact(pattern)
            else -> try {
                RegularExpression(pattern)
            } catch (_: Throwable) {
                // Regex delegates to each platform and does not expose one
                // common invalid-pattern exception type.
                Invalid(pattern)
            }
        }
    }
}

/** One directly decoded Codex Hook handler declaration. */
@Serializable
public sealed interface CodexCliHookHandler {
    /**
     * A command handler.
     *
     * @property command Portable command used on non-Windows platforms and as
     * the Windows fallback.
     * @property commandWindows Nullable because a platform-specific command is
     * optional; `null` means [command] is used on Windows.
     * @property commandWindowsAlias Nullable because the legacy snake-case
     * alias may be absent; `null` means no alias was declared.
     * @property timeoutSeconds Nullable because omission uses Codex's default
     * timeout; `null` means no per-handler timeout was configured.
     * @property statusMessage Nullable because a command need not expose a UI
     * status label; `null` means no custom label was configured.
     * @property additionalContextLimit Nullable because omission uses Codex's
     * default output limit; `null` means no per-handler override was configured.
     */
    @Serializable
    @SerialName("command")
    public data class Command(
        public val command: String,
        @SerialName("commandWindows")
        private val commandWindows: String? = null,
        @SerialName("command_windows")
        private val commandWindowsAlias: String? = null,
        @SerialName("timeout")
        public val timeoutSeconds: Long? = null,
        public val async: Boolean = false,
        @SerialName("statusMessage")
        public val statusMessage: String? = null,
        @SerialName("additionalContextLimit")
        public val additionalContextLimit: Int? = null,
    ) : CodexCliHookHandler {
        /**
         * Nullable because a platform-specific command is optional; `null`
         * means [command] is used on Windows.
         */
        @Transient
        public val windowsCommand: String? = commandWindows ?: commandWindowsAlias

        /** Command selected for the current host platform. */
        @Transient
        public val platformCommand: String =
            if (CodexCliStoragePlatform.isWindows) {
                windowsCommand ?: command
            } else {
                command
            }
    }

    /** Unsupported prompt handler preserved in the decoded configuration. */
    @Serializable
    @SerialName("prompt")
    public data object Prompt : CodexCliHookHandler

    /** Unsupported agent handler preserved in the decoded configuration. */
    @Serializable
    @SerialName("agent")
    public data object Agent : CodexCliHookHandler
}

/** One matcher and its ordered handler declarations. */
@Serializable
public data class CodexCliHookMatcherGroup(
    public val matcher: CodexCliHookMatcher = CodexCliHookMatcher.All,
    public val hooks: List<CodexCliHookHandler> = emptyList(),
)

/** Hook declarations grouped by their Codex invocation boundary. */
@Serializable
public data class CodexCliHookDeclarations(
    @SerialName("PreToolUse")
    public val preToolUse: List<CodexCliHookMatcherGroup> = emptyList(),
    @SerialName("PermissionRequest")
    public val permissionRequest: List<CodexCliHookMatcherGroup> = emptyList(),
    @SerialName("PostToolUse")
    public val postToolUse: List<CodexCliHookMatcherGroup> = emptyList(),
    @SerialName("PreCompact")
    public val preCompact: List<CodexCliHookMatcherGroup> = emptyList(),
    @SerialName("PostCompact")
    public val postCompact: List<CodexCliHookMatcherGroup> = emptyList(),
    @SerialName("SessionStart")
    public val sessionStart: List<CodexCliHookMatcherGroup> = emptyList(),
    @SerialName("SessionEnd")
    public val sessionEnd: List<CodexCliHookMatcherGroup> = emptyList(),
    @SerialName("UserPromptSubmit")
    public val userPromptSubmit: List<CodexCliHookMatcherGroup> = emptyList(),
    @SerialName("SubagentStart")
    public val subagentStart: List<CodexCliHookMatcherGroup> = emptyList(),
    @SerialName("SubagentStop")
    public val subagentStop: List<CodexCliHookMatcherGroup> = emptyList(),
    @SerialName("Stop")
    public val stop: List<CodexCliHookMatcherGroup> = emptyList(),
)

/**
 * One decoded Hook source, ordered as a configuration layer.
 *
 * [hooks] preserves all declarations from the source. The Hook implementation
 * later selects the command handlers it supports.
 */
@Serializable
public data class CodexCliHookLayer(
    @Serializable(with = CodexCliHookPathSerializer::class)
    public val sourcePath: Path,
    public val sourceKind: CodexCliHookSourceKind,
    public val environment: Map<String, String> = emptyMap(),
    /**
     * Nullable because inline `config.toml` Hook tables have no description;
     * `null` means the source did not declare one.
     */
    public val description: String? = null,
    public val hooks: CodexCliHookDeclarations = CodexCliHookDeclarations(),
)

/**
 * Shared serial shape of `hooks.json` and the Hook portion of `config.toml`.
 *
 * @property description Nullable because inline TOML and hooks files without a
 * label omit it; `null` means the source has no description.
 */
@Serializable
internal data class CodexCliHooksDocument(
    val description: String? = null,
    val hooks: CodexCliHookDeclarations = CodexCliHookDeclarations(),
)

private fun String.isExactCodexHookMatcher(): Boolean = all { character ->
    (character.isLetterOrDigit() && character.code < 128) ||
        character == '_' ||
        character == '|'
}

internal object CodexCliHookMatcherSerializer : KSerializer<CodexCliHookMatcher> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("CodexCliHookMatcher", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: CodexCliHookMatcher) {
        encoder.encodeString(value.pattern)
    }

    override fun deserialize(decoder: Decoder): CodexCliHookMatcher =
        CodexCliHookMatcher.parse(decoder.decodeString())
}

internal object CodexCliHookPathSerializer : KSerializer<Path> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("kotlinx.io.files.Path", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Path) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Path =
        Path(decoder.decodeString())
}

internal expect object CodexCliStoragePlatform {
    val isWindows: Boolean
}
