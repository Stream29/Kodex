package io.github.stream29.kodex.hook.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.jvm.JvmInline

/** Narrow global-settings view required by a configured Hook implementation. */
public interface HookSettings {
    /** Latest complete Hook configuration snapshot. */
    public val hooks: HookConfiguration
}

/** Complete Kodex-owned Hook configuration. */
@Serializable
public data class HookConfiguration(
    public val featureEnabled: Boolean = true,
    public val sources: List<HookSourceConfiguration> = emptyList(),
) {
    init {
        require(sources.map(HookSourceConfiguration::id).distinct().size == sources.size) {
            "Hook source ids must be unique."
        }
        val importIdentities = sources.mapNotNull(HookSourceConfiguration::importIdentity)
        require(importIdentities.distinct().size == importIdentities.size) {
            "Imported Hook source identities must be unique."
        }
    }
}

/** A persisted environment value whose diagnostic rendering never exposes its contents. */
@JvmInline
@Serializable
public value class HookEnvironmentValue(
    public val value: String,
) {
    override fun toString(): String = RedactedHookEnvironmentValue
}

/** Codex source category retained only as one-time import provenance. */
@Serializable
public enum class HookCodexSourceKind {
    @SerialName("user")
    User,

    @SerialName("project")
    Project,
}

/**
 * Identity of one Codex configuration source copied into Kodex.
 *
 * This identity is used only to classify later explicit imports. Runtime Hook
 * execution never reads [normalizedPath].
 */
@Serializable
public data class HookCodexImportIdentity(
    public val sourceKind: HookCodexSourceKind,
    public val normalizedPath: String,
) {
    init {
        require(normalizedPath.isNotBlank()) {
            "A Codex Hook import path must not be blank."
        }
    }

    /** Stable key used by import decisions without exposing Hook contents. */
    public val key: String
        get() = "${sourceKind.name.lowercase()}:$normalizedPath"
}

/** One independently enabled, ordered Hook configuration source. */
@Serializable
public data class HookSourceConfiguration(
    public val id: String,
    public val name: String,
    public val enabled: Boolean = true,
    public val importIdentity: HookCodexImportIdentity? = null,
    public val environment: Map<String, HookEnvironmentValue> = emptyMap(),
    public val hooks: HookDeclarations = HookDeclarations(),
) {
    init {
        require(id.isNotBlank()) { "A Hook source id must not be blank." }
        require(name.isNotBlank()) { "A Hook source name must not be blank." }
        require(environment.keys.all(String::isNotBlank)) {
            "Hook environment names must not be blank."
        }
    }
}

/** Hook invocation boundaries currently implemented by Kodex. */
public enum class HookEvent(
    public val wireName: String,
) {
    PreToolUse("PreToolUse"),
    PermissionRequest("PermissionRequest"),
    PostToolUse("PostToolUse"),
    PreCompact("PreCompact"),
    PostCompact("PostCompact"),
    UserPromptSubmit("UserPromptSubmit"),
    Stop("Stop"),
}

/** Hook declarations grouped by their invocation boundary. */
@Serializable
public data class HookDeclarations(
    @SerialName("PreToolUse")
    public val preToolUse: List<HookMatcherGroup> = emptyList(),
    @SerialName("PermissionRequest")
    public val permissionRequest: List<HookMatcherGroup> = emptyList(),
    @SerialName("PostToolUse")
    public val postToolUse: List<HookMatcherGroup> = emptyList(),
    @SerialName("PreCompact")
    public val preCompact: List<HookMatcherGroup> = emptyList(),
    @SerialName("PostCompact")
    public val postCompact: List<HookMatcherGroup> = emptyList(),
    @SerialName("UserPromptSubmit")
    public val userPromptSubmit: List<HookMatcherGroup> = emptyList(),
    @SerialName("Stop")
    public val stop: List<HookMatcherGroup> = emptyList(),
) {
    /** Ordered matcher groups for [event]. */
    public fun groups(event: HookEvent): List<HookMatcherGroup> =
        when (event) {
            HookEvent.PreToolUse -> preToolUse
            HookEvent.PermissionRequest -> permissionRequest
            HookEvent.PostToolUse -> postToolUse
            HookEvent.PreCompact -> preCompact
            HookEvent.PostCompact -> postCompact
            HookEvent.UserPromptSubmit -> userPromptSubmit
            HookEvent.Stop -> stop
        }

    /** Returns a copy whose [event] declaration is [groups]. */
    public fun withGroups(
        event: HookEvent,
        groups: List<HookMatcherGroup>,
    ): HookDeclarations =
        when (event) {
            HookEvent.PreToolUse -> copy(preToolUse = groups)
            HookEvent.PermissionRequest -> copy(permissionRequest = groups)
            HookEvent.PostToolUse -> copy(postToolUse = groups)
            HookEvent.PreCompact -> copy(preCompact = groups)
            HookEvent.PostCompact -> copy(postCompact = groups)
            HookEvent.UserPromptSubmit -> copy(userPromptSubmit = groups)
            HookEvent.Stop -> copy(stop = groups)
        }

    /** Number of command handlers retained by this source. */
    public val commandCount: Int
        get() = HookEvent.entries.sumOf { event ->
            groups(event).sumOf { group -> group.hooks.size }
        }

    /** Events with at least one retained command handler, in runtime order. */
    public val configuredEvents: List<HookEvent>
        get() = HookEvent.entries.filter { event ->
            groups(event).any { group -> group.hooks.isNotEmpty() }
        }
}

/** One matcher and its ordered command handlers. */
@Serializable
public data class HookMatcherGroup(
    public val matcher: HookMatcher = HookMatcher.All,
    public val hooks: List<HookCommandDefinition> = emptyList(),
)

/** One fully expanded command Hook persisted and executed by Kodex. */
@Serializable
public data class HookCommandDefinition(
    public val command: String,
    @SerialName("timeout_seconds")
    public val timeoutSeconds: Long = DefaultHookTimeoutSeconds,
    public val enabled: Boolean = true,
    @SerialName("status_message")
    public val statusMessage: String? = null,
    @SerialName("additional_context_limit")
    public val additionalContextLimit: Int? = null,
) {
    init {
        require(command.isNotBlank()) { "A Hook command must not be blank." }
        require(timeoutSeconds >= 1L) { "A Hook timeout must be at least one second." }
        require(additionalContextLimit == null || additionalContextLimit >= 0) {
            "A Hook additional-context limit must not be negative."
        }
    }
}

/**
 * Compiled matcher retained in Kodex settings.
 *
 * Simple `a|b` patterns use exact matching. Other valid patterns use the
 * platform regular-expression implementation.
 */
@Serializable(with = HookMatcherSerializer::class)
public sealed interface HookMatcher {
    /** Original configured pattern, normalized to `*` for [All]. */
    public val pattern: String

    public fun matches(inputs: List<String>): Boolean

    public data object All : HookMatcher {
        override val pattern: String = "*"

        override fun matches(inputs: List<String>): Boolean = true
    }

    public data class Exact(
        override val pattern: String,
    ) : HookMatcher {
        @Transient
        private val candidates: Set<String> = pattern.split('|').toSet()

        init {
            require(pattern.isExactHookMatcher()) {
                "Exact Hook matcher contains regular-expression syntax: $pattern"
            }
        }

        override fun matches(inputs: List<String>): Boolean = inputs.any(candidates::contains)
    }

    public data class RegularExpression(
        override val pattern: String,
    ) : HookMatcher {
        @Transient
        private val regex: Regex = Regex(pattern)

        override fun matches(inputs: List<String>): Boolean = inputs.any(regex::containsMatchIn)
    }

    /** Invalid imported pattern retained only for schema compatibility. */
    public data class Invalid(
        override val pattern: String,
    ) : HookMatcher {
        override fun matches(inputs: List<String>): Boolean = false
    }

    public companion object {
        /** Parses and compiles one matcher without throwing for invalid regular expressions. */
        public fun parse(pattern: String): HookMatcher =
            when {
                pattern.isEmpty() || pattern == "*" -> All
                pattern.isExactHookMatcher() -> Exact(pattern)
                else -> try {
                    RegularExpression(pattern)
                } catch (_: Throwable) {
                    // Regex delegates to each platform and exposes no common
                    // invalid-pattern exception type.
                    Invalid(pattern)
                }
            }
    }
}

private fun String.isExactHookMatcher(): Boolean = all { character ->
    (character.isLetterOrDigit() && character.code < 128) ||
        character == '_' ||
        character == '|'
}

internal object HookMatcherSerializer : KSerializer<HookMatcher> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("HookMatcher", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: HookMatcher) {
        encoder.encodeString(value.pattern)
    }

    override fun deserialize(decoder: Decoder): HookMatcher =
        HookMatcher.parse(decoder.decodeString())
}

public const val DefaultHookTimeoutSeconds: Long = 600L

private const val RedactedHookEnvironmentValue: String = "<redacted>"
