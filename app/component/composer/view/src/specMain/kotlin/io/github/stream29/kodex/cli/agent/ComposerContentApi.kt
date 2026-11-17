package io.github.stream29.kodex.cli.agent

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.app.agent.contract.ComposerState
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.cli.settings.NewLineKey

/**
 * Shared Composer composition and revision-bound interaction wiring.
 * Borrows its child: unmount never closes it or cancels work accepted by its owner.
 * Widget state is remounted on exact child/owner replacement, not on draft revision changes.
 * [frame] mounts content once and owns only renderer geometry and borrowed focus handles.
 */
@Composable
public expect fun ComposerContent(
    viewModel: ComposerViewModel,
    newLineKey: NewLineKey,
    autoFocus: Boolean = true,
    enabled: Boolean = true,
    maximumEditorLines: Int = 6,
    frame: @Composable (content: @Composable () -> Unit) -> Unit = { it() },
): Unit

/** Status copy, not a second submission/failure authority. */
public expect fun composerStatusLine(state: ComposerState): String?

/** Queue-ordered content, before renderer-specific single-line truncation. Never claims inputs. */
public expect fun pendingSteerPreviewContent(
    pending: List<AgentCommit.Steerable>,
    maximumRows: Int,
): List<String>
