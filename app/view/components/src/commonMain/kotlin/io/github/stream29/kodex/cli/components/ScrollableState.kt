@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

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

public actual class MutableScrollInteractionSource actual constructor(
    private val onInteractionCommitted: ((ScrollInteraction) -> Unit)?,
) : ScrollInteractionSource {
    public actual override val interactions: SharedFlow<ScrollInteraction>
        field = MutableSharedFlow(
            extraBufferCapacity = INTERACTION_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    public actual fun tryEmit(interaction: ScrollInteraction): Boolean {
        onInteractionCommitted?.invoke(interaction)
        return interactions.tryEmit(interaction)
    }

    private companion object {
        const val INTERACTION_BUFFER_CAPACITY: Int = 64
    }
}

public actual fun ScrollableState(
    consumeScrollDelta: (Int) -> Int,
    canScrollBackward: () -> Boolean,
    canScrollForward: () -> Boolean,
): ScrollableState = DefaultScrollableState(
    consumeScrollDelta = consumeScrollDelta,
    canScrollBackwardProvider = canScrollBackward,
    canScrollForwardProvider = canScrollForward,
)

@Composable
public actual fun rememberScrollableState(
    consumeScrollDelta: (Int) -> Int,
    canScrollBackward: () -> Boolean,
    canScrollForward: () -> Boolean,
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

@Stable
public actual class ScrollState actual constructor(initial: Int) : ScrollableState {
    private val valueState = mutableIntStateOf(initial)
    private val maxValueState = mutableIntStateOf(Int.MAX_VALUE)
    private val viewportSizeState = mutableIntStateOf(0)
    private val scrollInProgressState = mutableStateOf(false)

    init {
        require(initial >= 0) { "Initial scroll position cannot be negative." }
    }

    public actual val value: Int
        get() = valueState.intValue

    public actual val maxValue: Int
        get() = maxValueState.intValue

    public actual val viewportSize: Int
        get() = viewportSizeState.intValue

    public actual override val canScrollBackward: Boolean
        get() = value > 0

    public actual override val canScrollForward: Boolean
        get() = value < maxValue

    public actual override val isScrollInProgress: Boolean
        get() = scrollInProgressState.value

    public actual override fun scrollBy(delta: Int): Int {
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

    public actual fun scrollTo(value: Int): Int {
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

@Composable
public actual fun rememberScrollState(initial: Int): ScrollState = remember { ScrollState(initial) }

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
