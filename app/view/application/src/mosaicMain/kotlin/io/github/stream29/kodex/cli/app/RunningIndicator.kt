package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal val RunningIndicatorFrames: List<String> =
    listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

internal const val RunningIndicatorFrameDurationMillis: Int = 100

@Composable
internal fun rememberRunningIndicatorFrame(active: Boolean): State<String> {
    val frame = remember { mutableStateOf(RunningIndicatorFrames.first()) }
    LaunchedEffect(active) {
        frame.value = RunningIndicatorFrames.first()
        if (!active) return@LaunchedEffect

        var frameIndex = 0
        while (isActive) {
            delay(RunningIndicatorFrameDurationMillis.toLong())
            frameIndex = (frameIndex + 1) % RunningIndicatorFrames.size
            frame.value = RunningIndicatorFrames[frameIndex]
        }
    }
    return frame
}

internal fun runningIndicatorLabel(
    name: String,
    running: Boolean,
    frame: String,
): String = if (running) frame + name else name
