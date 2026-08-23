package io.github.stream29.kodex.app.history.contract.item

import kotlin.time.Duration

/** One virtual timestamp footer projected from turn markers and stable timestamps. */
public data class TurnTimeMarkerHistoryItemViewModel(
    /** Source turn-marker index. */
    public val markerIndex: Int,
    /** Last stable item in the projected turn. */
    public val endIndex: Int,
    public val duration: Duration,
) : HistoryItemViewModel {
    init {
        requireHistoryItemIndex(markerIndex)
        requireHistoryItemIndex(endIndex)
        require(endIndex > markerIndex) {
            "A turn time marker must follow at least one stable item in its turn."
        }
        requireHistoryItemElapsed(duration)
    }
}
