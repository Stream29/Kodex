package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.cli.history.HistoryDisclosureState

@Composable
internal fun HistoryToolDisclosureMark(
    state: HistoryDisclosureState,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(Modifier.width(20.dp * LocalDensity.current.fontScale)) {
        if (state == HistoryDisclosureState.Loading) {
            HistoryDisclosureIndicator(state, color)
        } else {
            Text(
                if (state == HistoryDisclosureState.Expanded) "v" else ">",
                modifier = Modifier.clearAndSetSemantics {
                    testTag = "history-tool-disclosure-${state.name}"
                },
                color = color,
            )
        }
    }
}
