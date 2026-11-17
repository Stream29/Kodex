package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import kotlinx.serialization.Serializable

/** Complete stable event stored on the work timeline. */
@Serializable
public sealed interface WorkCommit : AgentCommit
