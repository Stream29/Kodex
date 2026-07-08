package io.github.stream29.codex.lite.agentstorage.cleanmodels

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stable clean projection of an OpenAI `reasoning` item.
 *
 * OpenAI exposes user-facing reasoning summary text through `summary_text`.
 * Raw reasoning content is not part of this model because the hosted Codex
 * backend does not normally expose it.
 *
 * @property display User-facing reasoning text after projection has merged
 * provider summary parts.
 */
@Serializable
@SerialName("reasoning")
public data class StableReasoning(
    public val display: String,
) : StableCleanEvent
