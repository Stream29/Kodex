@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.SharedFlow

/**
 * Consumes scrolling measured in terminal cells along one logical axis.
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

/** Axis interpreted by scrolling input. */
public enum class ScrollOrientation {
    Vertical,
    Horizontal,
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
 * [onInteractionCommitted] runs synchronously before the interaction is published to
 * [interactions].
 */
public expect class MutableScrollInteractionSource(
    onInteractionCommitted: ((ScrollInteraction) -> Unit)? = null,
) : ScrollInteractionSource {
    public override val interactions: SharedFlow<ScrollInteraction>

    public fun tryEmit(interaction: ScrollInteraction): Boolean
}

/** Creates a [ScrollableState] around caller-owned position state. */
public expect fun ScrollableState(
    consumeScrollDelta: (Int) -> Int,
    canScrollBackward: () -> Boolean = { true },
    canScrollForward: () -> Boolean = { true },
): ScrollableState

/** Remembers a [ScrollableState] while always invoking the latest callbacks. */
@Composable
public expect fun rememberScrollableState(
    consumeScrollDelta: (Int) -> Int,
    canScrollBackward: () -> Boolean = { true },
    canScrollForward: () -> Boolean = { true },
): ScrollableState

/** Absolute terminal-cell position for an eagerly measured scrolling container. */
@Stable
public expect class ScrollState(initial: Int = 0) : ScrollableState {
    public val value: Int

    public val maxValue: Int

    public val viewportSize: Int

    public override val canScrollBackward: Boolean

    public override val canScrollForward: Boolean

    public override val isScrollInProgress: Boolean

    public override fun scrollBy(delta: Int): Int

    public fun scrollTo(value: Int): Int
}

/** Remembers an eagerly measured [ScrollState]. */
@Composable
public expect fun rememberScrollState(initial: Int = 0): ScrollState
