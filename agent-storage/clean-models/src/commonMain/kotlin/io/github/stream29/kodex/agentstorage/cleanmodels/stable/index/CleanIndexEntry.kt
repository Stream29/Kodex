package io.github.stream29.kodex.agentstorage.cleanmodels.stable.index

import kotlinx.serialization.Serializable

/**
 * Value stored on the sparse index timeline.
 *
 * An entry is either a complete stable index event or a compaction point.
 * Compaction points are storage metadata and are not OpenAI history events.
 */
@Serializable
public sealed interface CleanIndexEntry
