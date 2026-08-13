package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.animation.LinearEasing
import com.jakewharton.mosaic.animation.RepeatMode
import com.jakewharton.mosaic.animation.VectorConverter
import com.jakewharton.mosaic.animation.animateValue
import com.jakewharton.mosaic.animation.infiniteRepeatable
import com.jakewharton.mosaic.animation.rememberInfiniteTransition
import com.jakewharton.mosaic.animation.tween

internal val RunningIndicatorFrames: List<String> =
    listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

internal const val RunningIndicatorFrameDurationMillis: Int = 100

@Composable
internal fun rememberRunningIndicatorFrame(active: Boolean): State<String> {
    if (!active) {
        return remember { mutableStateOf(RunningIndicatorFrames.first()) }
    }

    val transition = rememberInfiniteTransition(label = "running indicator")
    val animationSpec = remember {
        infiniteRepeatable<Int>(
            animation = tween(
                durationMillis = RunningIndicatorFrames.size * RunningIndicatorFrameDurationMillis,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        )
    }
    val frameIndex = transition.animateValue(
        initialValue = 0,
        targetValue = RunningIndicatorFrames.size,
        typeConverter = Int.VectorConverter,
        animationSpec = animationSpec,
        label = "running indicator frame",
    )
    return remember(frameIndex) {
        derivedStateOf {
            RunningIndicatorFrames[frameIndex.value % RunningIndicatorFrames.size]
        }
    }
}

internal fun runningIndicatorLabel(
    name: String,
    running: Boolean,
    frame: String,
): String = if (running) frame + name else name
