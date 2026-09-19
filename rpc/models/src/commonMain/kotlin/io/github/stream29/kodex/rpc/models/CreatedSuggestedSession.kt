package io.github.stream29.kodex.rpc.models

import io.github.stream29.kodex.tool.multiagent.SuggestedSessionMeta
import kotlinx.serialization.Serializable

/**
 * One created child in the input order of a suggested-Session batch.
 *
 * [sessionIndex] supports frontend navigation and subsequent Session RPCs; [meta] is the
 * original tool-result metadata captured during creation, not a live title or execution state.
 * The value is neither a keepalive lease nor proof that the child's model run succeeded.
 */
@Serializable
public data class CreatedSuggestedSession(
    public val sessionIndex: Int,
    public val meta: SuggestedSessionMeta,
)
