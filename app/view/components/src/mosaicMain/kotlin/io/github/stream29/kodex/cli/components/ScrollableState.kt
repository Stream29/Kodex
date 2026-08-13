package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Consumes scrolling measured in terminal rows.
 *
 * Positive deltas move toward the logical end and negative deltas move toward the logical start.
 * [scrollBy] returns the signed portion that was consumed.
 */
@Stable
public interface ScrollableState {
    public val canScrollBackward: Boolean
    public val canScrollForward: Boolean
    public val isScrollInProgress: Boolean

    public fun scrollBy(delta: Int): Int
}

/** Source that initiated a [ScrollInteraction]. */
public enum class ScrollInputSource(public val isUserInitiated: Boolean) {
    Pointer(isUserInitiated = true),
    Keyboard(isUserInitiated = true),
    FocusRelocation(isUserInitiated = true),
    Programmatic(isUserInitiated = false),
}

/**
 * A committed scroll mutation.
 *
 * Zero-consumption attempts are not interactions. A future request-based position change may emit
 * an interaction when it changes pending intent, even before layout resolves its consumed delta.
 */
public data class ScrollInteraction(
    public val source: ScrollInputSource,
    public val orientation: ScrollOrientation,
    public val requestedDelta: Int,
    public val consumedDelta: Int,
)

/** Read-only stream of committed scroll interactions. */
public interface ScrollInteractionSource {
    public val interactions: SharedFlow<ScrollInteraction>
}

/**
 * Mutable publisher used by scrolling input and layout adapters.
 *
 * [onInteractionCommitted] runs synchronously before the interaction is published to [interactions].
 */
public class MutableScrollInteractionSource(
    private val onInteractionCommitted: ((ScrollInteraction) -> Unit)? = null,
) : ScrollInteractionSource {
    override val interactions: SharedFlow<ScrollInteraction>
        field = MutableSharedFlow(
            extraBufferCapacity = INTERACTION_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    public fun tryEmit(interaction: ScrollInteraction): Boolean {
        onInteractionCommitted?.invoke(interaction)
        return interactions.tryEmit(interaction)
    }

    private companion object {
        const val INTERACTION_BUFFER_CAPACITY: Int = 64
    }
}

/** Creates a [ScrollableState] around caller-owned position state. */
public fun ScrollableState(
    consumeScrollDelta: (Int) -> Int,
    canScrollBackward: () -> Boolean = { true },
    canScrollForward: () -> Boolean = { true },
): ScrollableState = DefaultScrollableState(
    consumeScrollDelta = consumeScrollDelta,
    canScrollBackwardProvider = canScrollBackward,
    canScrollForwardProvider = canScrollForward,
)

/** Remembers a [ScrollableState] while always invoking the latest callbacks. */
@Composable
public fun rememberScrollableState(
    consumeScrollDelta: (Int) -> Int,
    canScrollBackward: () -> Boolean = { true },
    canScrollForward: () -> Boolean = { true },
): ScrollableState {
    val latestConsumeScrollDelta = rememberUpdatedState(consumeScrollDelta)
    val latestCanScrollBackward = rememberUpdatedState(canScrollBackward)
    val latestCanScrollForward = rememberUpdatedState(canScrollForward)
    return remember {
        ScrollableState(
            consumeScrollDelta = { delta -> latestConsumeScrollDelta.value(delta) },
            canScrollBackward = { latestCanScrollBackward.value() },
            canScrollForward = { latestCanScrollForward.value() },
        )
    }
}

/** Absolute row position for an eagerly measured scrolling container. */
@Stable
public class ScrollState(initial: Int = 0) : ScrollableState {
    private val valueState = mutableIntStateOf(initial)
    private val maxValueState = mutableIntStateOf(Int.MAX_VALUE)
    private val viewportSizeState = mutableIntStateOf(0)
    private val scrollInProgressState = mutableStateOf(false)

    init {
        require(initial >= 0) { "Initial scroll position cannot be negative." }
    }

    public val value: Int
        get() = valueState.intValue

    public val maxValue: Int
        get() = maxValueState.intValue

    public val viewportSize: Int
        get() = viewportSizeState.intValue

    override val canScrollBackward: Boolean
        get() = value > 0

    override val canScrollForward: Boolean
        get() = value < maxValue

    override val isScrollInProgress: Boolean
        get() = scrollInProgressState.value

    override fun scrollBy(delta: Int): Int {
        if (delta == 0) return 0
        val target = (value.toLong() + delta).coerceIn(0L, maxValue.toLong()).toInt()
        val consumed = target - value
        if (consumed == 0) return 0
        scrollInProgressState.value = true
        return try {
            valueState.intValue = target
            consumed
        } finally {
            scrollInProgressState.value = false
        }
    }

    public fun scrollTo(value: Int): Int {
        val target = value.coerceIn(0, maxValue)
        return scrollBy(target - this.value)
    }

    internal fun updateBounds(maxValue: Int, viewportSize: Int) {
        require(maxValue >= 0) { "Maximum scroll position cannot be negative." }
        require(viewportSize >= 0) { "Viewport size cannot be negative." }
        maxValueState.intValue = maxValue
        viewportSizeState.intValue = viewportSize
        if (value > maxValue) valueState.intValue = maxValue
    }
}

/** Remembers an eagerly measured [ScrollState]. */
@Composable
public fun rememberScrollState(initial: Int = 0): ScrollState = remember { ScrollState(initial) }

private class DefaultScrollableState(
    private val consumeScrollDelta: (Int) -> Int,
    private val canScrollBackwardProvider: () -> Boolean,
    private val canScrollForwardProvider: () -> Boolean,
) : ScrollableState {
    private val scrollInProgressState = mutableStateOf(false)

    override val canScrollBackward: Boolean
        get() = canScrollBackwardProvider()

    override val canScrollForward: Boolean
        get() = canScrollForwardProvider()

    override val isScrollInProgress: Boolean
        get() = scrollInProgressState.value

    override fun scrollBy(delta: Int): Int {
        if (delta == 0) return 0
        scrollInProgressState.value = true
        return try {
            consumeScrollDelta(delta).also { consumed ->
                requireValidConsumption(delta, consumed)
            }
        } finally {
            scrollInProgressState.value = false
        }
    }
}

private fun requireValidConsumption(requested: Int, consumed: Int) {
    val valid = if (requested > 0) {
        consumed in 0..requested
    } else {
        consumed in requested..0
    }
    require(valid) {
        "Consumed scroll delta $consumed must have the direction and magnitude of requested delta $requested."
    }
}
