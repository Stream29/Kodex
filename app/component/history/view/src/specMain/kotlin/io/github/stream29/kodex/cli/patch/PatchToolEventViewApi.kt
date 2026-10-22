package io.github.stream29.kodex.cli.patch

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolEvent
import io.github.stream29.kodex.utils.applypatch.Patch

/** Renders an apply-patch invocation whose result is still pending. */
@Composable
public expect fun PendingPatchToolEventView(diff: Patch): Unit

/** Renders a completed apply-patch clean event. */
@Composable
public expect fun StablePatchToolEventView(
    event: StablePatchToolEvent,
    expanded: Boolean? = null,
    onToggleExpanded: (() -> Unit)? = null,
    headerTrailingText: String? = null,
): Unit
