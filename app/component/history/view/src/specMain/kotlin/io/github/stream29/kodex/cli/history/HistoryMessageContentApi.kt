package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.app.ui.UiProse
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.app.ui.UiTextRole
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemViewModel
import kotlin.time.Duration

/**
 * Shared message composition from its real typed event, without a flattened history cache.
 * Slots own only heading/body typography and measurement; supporting text marks developer content.
 * Inline media references are bounded, not decoded or fetched as arbitrary external URLs.
 */
@Composable
public expect fun HistoryMessageContent(
    event: StableIndexEvent.Steerable,
    heading: @Composable (String) -> Unit = { UiText(it, UiTextRole.Title, maximumLines = 1) },
    body: @Composable (String, supporting: Boolean) -> Unit = { text, supporting ->
        UiProse(text, if (supporting) UiTextRole.Supporting else UiTextRole.Body)
    },
): Unit

/** Observes the exact lazy child's Loading/Ready/Failed states; does not start or cache its read. */
@Composable
public expect fun HistoryMessageRow(
    model: MessageHistoryItemViewModel,
    heading: @Composable (String, elapsed: Duration) -> Unit = { text, _ ->
        UiText(text, UiTextRole.Title, maximumLines = 1)
    },
    body: @Composable (String, supporting: Boolean) -> Unit = { text, supporting ->
        UiProse(text, if (supporting) UiTextRole.Supporting else UiTextRole.Body)
    },
    turnMarker: @Composable (Duration) -> Unit = { HistoryTurnMarker(it) },
): Unit

/** Complete-turn marker with the existing nearest-millisecond rounding. */
@Composable
public expect fun HistoryTurnMarker(duration: Duration): Unit
