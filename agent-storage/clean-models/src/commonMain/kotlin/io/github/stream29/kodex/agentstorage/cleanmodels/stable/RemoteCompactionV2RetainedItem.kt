package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import kotlinx.serialization.Serializable

/**
 * Stable clean events retained as the clear-text prefix after remote
 * compaction v2.
 */
@Serializable
public sealed interface RemoteCompactionV2RetainedItem : StableCleanEvent
