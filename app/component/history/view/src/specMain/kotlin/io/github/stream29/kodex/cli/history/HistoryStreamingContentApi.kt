package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.history.contract.HistoryStreamingKind
import io.github.stream29.kodex.app.ui.UiProse
import io.github.stream29.kodex.app.ui.UiTextRole
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonElement

/** Renderer-local replay accumulator result, not a ViewModel projection or RPC DTO. */
public data class StreamingResponseSnapshot(
    public val item: ResponseItem? = null,
    public val messageParts: List<StreamingTextPart> = emptyList(),
    public val reasoningSummaryParts: List<StreamingTextPart> = emptyList(),
    public val toolInputParts: List<StreamingTextPart> = emptyList(),
    public val webSearchStatus: String? = null,
    public val unknownPayload: JsonElement? = null,
)

public data class StreamingTextPart(public val key: String, public val text: String)

/** One replay collector per stream identity, with conflated 50ms visual updates and no event loss. */
@Composable
public expect fun rememberStreamingSnapshot(
    events: SharedFlow<ResponsesStreamEvent>,
    onContentChange: () -> Unit = {},
): StreamingResponseSnapshot

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
): Unit
