package io.github.stream29.kodex.app.sessiondelete

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteViewModel

/**
 * Shared confirmation content for the captured child. Disposal closes that child, never its
 * dependency. Cancel does not delete; a normal result is forwarded unchanged while active.
 * Failure/cancellation does not invoke [onResult]. Only the host decides whether to dismiss.
 * [frame] mounts content once and owns only geometry/painting, never the child's lifetime.
 */
@Composable
public expect fun SessionDeleteContent(
    viewModel: SessionDeleteViewModel,
    onCancel: () -> Unit,
    onResult: (Boolean) -> Unit,
    frame: @Composable (content: @Composable () -> Unit) -> Unit = { it() },
): Unit
