package io.github.stream29.kodex.cli.sessioncatalog

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionGroupFocus
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionGroupsViewModel

/** Mount only while the Sessions tool window has a visible panel. */
@Composable
public expect fun SessionGroupsContent(
    viewModel: SessionGroupsViewModel,
    focus: SessionGroupFocus?,
    renderer: SessionGroupsRenderer,
    onNewInGroup: suspend (String?) -> Unit,
    onCollapse: () -> Unit,
    onFailure: (Throwable) -> Unit,
)
