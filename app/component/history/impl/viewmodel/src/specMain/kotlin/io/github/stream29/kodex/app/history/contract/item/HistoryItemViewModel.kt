package io.github.stream29.kodex.app.history.contract.item

import kotlin.time.Duration

/**
 * Contract root for one first-level History row.
 *
 * Stateful direct subtypes own dedicated state unions. Immutable single-state rows are direct data
 * classes. This root deliberately exposes no common identity, payload, state, or expansion API.
 * Initial loading is implementation-owned; the View only collects subtype state and sends explicit
 * expansion commands. Every item elapsed duration is non-null; the first stable item uses
 * [Duration.ZERO].
 */
public sealed interface HistoryItemViewModel

/** Stable indexed item which may be nested in a folded [WorkGroupHistoryItemViewModel]. */
public sealed interface WorkGroupChildHistoryItemViewModel : HistoryItemViewModel {
    public val index: Int
}

internal fun requireHistoryItemIndex(index: Int) {
    require(index >= 0) { "A history item index must be non-negative." }
}

internal fun requireHistoryItemElapsed(elapsed: Duration) {
    require(elapsed >= Duration.ZERO && elapsed.isFinite()) {
        "A history item elapsed duration must be finite and non-negative."
    }
}
