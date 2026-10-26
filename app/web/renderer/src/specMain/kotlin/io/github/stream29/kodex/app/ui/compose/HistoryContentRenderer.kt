package io.github.stream29.kodex.app.ui.compose

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.cli.history.HistoryContentRenderer
import kotlin.time.Duration

@Composable
public expect fun rememberComposeHistoryContentRenderer(
    elapsed: Duration? = null, expanded: Boolean? = null, onToggleExpanded: (() -> Unit)? = null,
): HistoryContentRenderer
