package io.github.stream29.kodex.openai

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Codex metadata attached to a Responses API request.
 *
 * @property installationId Nullable because Codex identity metadata is
 * optional; `null` means `installation_id` is omitted for identified requests.
 * @property sessionId Nullable because Codex identity metadata is optional;
 * `null` means `session_id` is omitted for identified requests.
 * @property threadId Provider-facing thread identity. Detached memory metadata
 * omits it from the wire.
 * @property turnId Nullable because metadata may exist outside a logical user
 * turn; `null` means `turn_id` is omitted. Detached memory metadata always
 * omits it from the wire.
 * @property windowId Identity of the active context window. Detached memory
 * metadata omits it from the wire.
 * @property requestKind Request purpose and its request-specific metadata.
 */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = CodexResponsesMetadataSerializer::class)
public data class CodexResponsesMetadata(
    @SerialName("installation_id")
    public val installationId: String? = null,
    @SerialName("session_id")
    public val sessionId: String? = null,
    @SerialName("thread_id")
    public val threadId: String,
    @SerialName("turn_id")
    public val turnId: String? = null,
    @SerialName("window_id")
    public val windowId: String,
    @SerialName("request_kind")
    public val requestKind: CodexResponsesRequestKind,
)

/**
 * Structured Codex projection carried by Responses API `client_metadata`.
 *
 * @property installationId Nullable because Codex identity metadata is
 * optional; `null` means `x-codex-installation-id` is omitted.
 * @property sessionId Nullable because Codex identity metadata is optional;
 * `null` means `session_id` is omitted.
 * @property threadId Provider-facing thread identity.
 * @property turnId Nullable because metadata may exist outside a logical user
 * turn; `null` means `turn_id` is omitted.
 * @property windowId Identity of the active context window.
 * @property turnMetadata Serialized [CodexResponsesMetadata] transported by
 * the protocol as the `x-codex-turn-metadata` string field.
 */
@Serializable
public data class CodexResponsesClientMetadata(
    @SerialName("x-codex-installation-id")
    public val installationId: String? = null,
    @SerialName("session_id")
    public val sessionId: String? = null,
    @SerialName("thread_id")
    public val threadId: String,
    @SerialName("turn_id")
    public val turnId: String? = null,
    @SerialName("x-codex-window-id")
    public val windowId: String,
    @SerialName("x-codex-turn-metadata")
    public val turnMetadata: String,
)

/**
 * Purpose of a Codex Responses request.
 *
 * [Compaction] is the only branch carrying compaction metadata, so invalid
 * combinations cannot be represented.
 */
@Serializable
public sealed interface CodexResponsesRequestKind {
    @Serializable
    @SerialName("turn")
    public data object Turn : CodexResponsesRequestKind

    @Serializable
    @SerialName("prewarm")
    public data object Prewarm : CodexResponsesRequestKind

    @Serializable
    @SerialName("compaction")
    public data class Compaction(
        public val metadata: CompactionTurnMetadata,
    ) : CodexResponsesRequestKind

    @Serializable
    @SerialName("memory")
    public data object Memory : CodexResponsesRequestKind
}

/**
 * Metadata describing a compaction request at dispatch time.
 */
@Serializable
public data class CompactionTurnMetadata(
    public val trigger: CompactionTrigger,
    public val reason: CompactionReason,
    public val implementation: CompactionImplementation,
    public val phase: CompactionPhase,
    public val strategy: CompactionStrategy,
)

@Serializable
public enum class CompactionImplementation {
    @SerialName("responses")
    Responses,

    @SerialName("responses_compaction_v2")
    ResponsesCompactionV2,

    @SerialName("responses_compact")
    ResponsesCompact,
}

@Serializable
public enum class CompactionStrategy {
    @SerialName("memento")
    Memento,

    @SerialName("prefix_compaction")
    PrefixCompaction,
}
