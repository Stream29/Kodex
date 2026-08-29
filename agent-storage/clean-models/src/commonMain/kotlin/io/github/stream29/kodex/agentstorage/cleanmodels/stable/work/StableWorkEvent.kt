package io.github.stream29.kodex.agentstorage.cleanmodels.stable.work

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import kotlinx.serialization.Serializable

/** Complete stable event stored on the work timeline. */
@Serializable
public sealed interface StableWorkEvent : StableCleanEvent {
    /** Completed tool event stored on the work timeline. */
    @Serializable
    public sealed interface CompletedTool :
        StableWorkEvent,
        StableCleanEvent.CompletedTool.Work
}
