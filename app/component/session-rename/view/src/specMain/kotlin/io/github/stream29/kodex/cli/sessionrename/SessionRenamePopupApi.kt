package io.github.stream29.kodex.cli.sessionrename

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.BoxScope
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameViewModel

/** Renderer-only layout choices; neither changes the component's operation contract. */
public enum class SessionRenamePresentation {
    Compact,
    Labeled,
}

/**
 * Renders the spec's draft; only cursor/layout are local. [onSubmitted] is called
 * after a normal operation return, not after a persistence acknowledgment.
 * The host handles its exact popup/revision; exceptions leave the editor open.
 * Removal from composition closes the child, never its dependency.
 */
@Composable
public expect fun BoxScope.SessionRenamePopup(
    viewModel: SessionRenameViewModel,
    onDismissRequest: () -> Unit,
    onSubmitted: () -> Unit,
    presentation: SessionRenamePresentation = SessionRenamePresentation.Compact,
): Unit
