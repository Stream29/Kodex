package io.github.stream29.codex.lite.agentstorage.cleanmodels

import kotlinx.serialization.Serializable

/**
 * Stable clean marker for a context compaction boundary.
 *
 * This marker records that earlier raw history has been replaced in the
 * model-visible prompt by a compaction checkpoint. The checkpoint payload and
 * encrypted compacted context stay in raw storage rather than this clean model.
 */
@Serializable
public data object StableContextCompaction
