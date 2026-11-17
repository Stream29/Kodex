package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.history.contract.HistoryStreamingKind
import io.github.stream29.kodex.app.ui.UiProse
import io.github.stream29.kodex.app.ui.UiTextRole
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import kotlinx.coroutines.flow.SharedFlow

/** Message/agent/reasoning composition; tool and unknown presentation use their own renderer. */
@Composable
public expect fun HistoryStreamingTextContent(
    kind: HistoryStreamingKind,
    events: SharedFlow<ResponsesStreamEvent>,
    onContentChange: () -> Unit = {},
    heading: @Composable (String, supporting: Boolean) -> Unit = { text, supporting ->
        UiProse(text, if (supporting) UiTextRole.Supporting else UiTextRole.Title)
    },
    body: @Composable (String, supporting: Boolean) -> Unit = { text, supporting ->
        UiProse(text, if (supporting) UiTextRole.Supporting else UiTextRole.Body)
    },
    assistantBody: @Composable (String) -> Unit = { text -> body(text, false) },
    assistantEligibility: @Composable (Boolean?) -> Unit = {},
): Unit
