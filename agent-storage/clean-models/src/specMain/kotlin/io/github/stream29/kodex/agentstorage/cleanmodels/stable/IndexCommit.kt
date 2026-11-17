package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import kotlinx.serialization.Serializable

/** Complete stable event stored directly on the index timeline. */
@Serializable
public sealed interface IndexCommit : AgentCommit
