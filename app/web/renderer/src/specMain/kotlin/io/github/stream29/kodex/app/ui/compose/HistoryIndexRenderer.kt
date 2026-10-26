package io.github.stream29.kodex.app.ui.compose

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.cli.historyindex.*

/** Host owns hover grace and popup priority; rows only emit their captured interactions. */
@Composable
public expect fun rememberComposeHistoryIndexRenderer(
    onHoverChanged: (HistoryIndexInteraction, Boolean) -> Unit = { _, _ -> },
    onOpenMenu: (HistoryIndexMenuOpening) -> Unit = {},
): HistoryIndexRenderer
