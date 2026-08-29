package io.github.stream29.kodex.agentstorage.cleanmodels.stable.index

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import kotlinx.serialization.Serializable

/** Complete stable event stored directly on the index timeline. */
@Serializable
public sealed interface StableIndexEvent : StableCleanEvent, CleanIndexEntry {
    /** Completed tool event stored on the index timeline. */
    @Serializable
    public sealed interface CompletedTool :
        StableIndexEvent,
        StableCleanEvent.CompletedTool.Index

    /** Clean input that may be delivered into an active logical turn. */
    @Serializable
    public sealed interface Steerable : StableIndexEvent
}
