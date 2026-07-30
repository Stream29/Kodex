package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.jakewharton.mosaic.animation.animateIntAsState

/**
 * Animates an integer with a spring while keeping every emitted frame inside [minimum]..[maximum].
 *
 * Mosaic's current snapshot can produce an invalid transient value when an integer spring changes
 * direction rapidly. Clamp the animation output before it is used as a layout dimension.
 */
@Composable
public fun safeSpringInt(
    targetValue: Int,
    minimum: Int,
    maximum: Int,
    label: String = "SafeIntSpring",
): Int {
    require(minimum <= maximum) { "minimum must not exceed maximum" }
    val animatedValue by animateIntAsState(
        targetValue = targetValue.coerceIn(minimum, maximum),
        label = label,
    )
    return animatedValue.coerceIn(minimum, maximum)
}
