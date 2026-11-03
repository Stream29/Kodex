package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.cli.history.HistoryDisclosureState

@Composable
internal fun HistoryDisclosureIndicator(state: HistoryDisclosureState, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    if (state == HistoryDisclosureState.Loading) {
        CircularProgressIndicator(Modifier.size(12.dp).clearAndSetSemantics { }, color = color, strokeWidth = 2.dp)
    } else {
        Canvas(Modifier.size(12.dp)) {
            val start = if (state == HistoryDisclosureState.Expanded) Offset(size.width * .2f, size.height * .35f)
                else Offset(size.width * .35f, size.height * .2f)
            val middle = if (state == HistoryDisclosureState.Expanded) Offset(size.width * .5f, size.height * .65f)
                else Offset(size.width * .65f, size.height * .5f)
            val end = if (state == HistoryDisclosureState.Expanded) Offset(size.width * .8f, size.height * .35f)
                else Offset(size.width * .35f, size.height * .8f)
            drawLine(color, start, middle, strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
            drawLine(color, middle, end, strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}
