package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun RunningSpinner() {
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(100)
            frame = (frame + 1) % 10
        }
    }
    val color = LocalContentColor.current
    Canvas(Modifier.size(12.dp).semantics(mergeDescendants = true) {
        contentDescription = "Running"
        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
    }) {
        val stroke = 1.5.dp.toPx()
        drawArc(
            color = color, startAngle = frame * 36f - 90f, sweepAngle = 270f, useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f)),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}
