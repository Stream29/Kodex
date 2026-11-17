package io.github.stream29.kodex.agentstorage.cleanmodels.unstable

import io.github.stream29.kodex.openai.ResponseItem
import kotlinx.serialization.Serializable

/**
 * Durable record in the unfinished tool tail of an unstable snapshot.
 *
 * Unlike stable events, an unstable event is removed when the matching tool
 * interaction becomes complete. Some hosted calls have no client-executable
 * `call_id`, so they share this root without being [PendingToolCall]s.
 */
@Serializable
public sealed interface PendingRecord {
    /** Restores the model-visible call without a result. */
    public fun toResponseHistoryItems(): List<ResponseItem.HistoryItem>
}
