package io.github.stream29.kodex.app.sessionrename

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameViewModel
import io.github.stream29.kodex.cli.sessionrename.SessionRenamePresentation

/**
 * Shared draft/editor composition. Plain Enter snapshots a nonblank draft before subsequent
 * input events; normal return reports [onSubmitted] only while the captured child is active.
 * Unmount/replacement closes that child, not its dependency; only the host dismisses its popup.
 * [frame] mounts content once and owns only geometry/painting, never the child's lifetime.
 */
@Composable
public expect fun SessionRenameContent(
    viewModel: SessionRenameViewModel,
    onSubmitted: () -> Unit,
    presentation: SessionRenamePresentation = SessionRenamePresentation.Compact,
    frame: @Composable (content: @Composable () -> Unit) -> Unit = { it() },
): Unit
