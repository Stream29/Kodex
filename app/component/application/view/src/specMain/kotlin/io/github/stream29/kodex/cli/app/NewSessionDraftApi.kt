package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.cli.settings.NewLineKey

/**
 * Borrows the singleton draft; unmount retains its cursor and accepted owner work.
 * Submit confirms the current widget buffer synchronously, then passes its exact revision
 * to the New Session owner, including blank/raw text. It never calls persisted-Agent submit.
 * [frame] mounts the editor once and owns only renderer geometry.
 */
@Composable
public expect fun NewSessionDraft(
    composerViewModel: ComposerViewModel,
    newLineKey: NewLineKey,
    onSubmit: (expectedRevision: Long) -> Unit,
    maximumEditorLines: Int = 6,
    frame: @Composable (content: @Composable () -> Unit) -> Unit = { it() },
): Unit
