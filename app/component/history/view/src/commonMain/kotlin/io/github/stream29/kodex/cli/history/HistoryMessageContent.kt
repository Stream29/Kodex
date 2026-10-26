package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAgentMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableDeveloperMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.app.ui.UiColumn
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.app.ui.UiTextRole
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemState
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.ContentItem
import kotlin.time.Duration

@Composable
public actual fun HistoryMessageRow(
    model: MessageHistoryItemViewModel,
    heading: @Composable (String, elapsed: Duration) -> Unit,
    body: @Composable (String, supporting: Boolean) -> Unit,
    turnMarker: @Composable (Duration) -> Unit,
) {
    val state by model.state.collectAsState()
    when (val current = state) {
        is MessageHistoryItemState.Loading -> UiText("")
        MessageHistoryItemState.Failed -> UiText("Error", UiTextRole.Error)
        is MessageHistoryItemState.Ready -> UiColumn {
            HistoryMessageContent(current.event, { heading(it, current.elapsed) }, body)
            current.turnDuration?.let { turnMarker(it) }
        }
    }
}

@Composable
public actual fun HistoryTurnMarker(duration: Duration) {
    if (!duration.isFinite()) {
        UiText("---Worked for $duration---", UiTextRole.Supporting)
        return
    }
    UiText("---Worked for ${duration.roundToMilliseconds()}---", UiTextRole.Supporting)
}

@Composable
public actual fun HistoryMessageContent(
    event: StableIndexEvent.Steerable,
    heading: @Composable (String) -> Unit,
    body: @Composable (String, supporting: Boolean) -> Unit,
) {
    UiColumn {
        when (event) {
            is StableUserMessage -> Message("User", event.content, false, heading, body)
            is StableAssistantMessage -> Message("Assistant", event.content, false, heading, body)
            is StableDeveloperMessage -> Message("Developer", event.content, true, heading, body)
            is StableAgentMessage -> {
                heading("Agent ${event.author} → ${event.recipient}")
                event.content.forEach {
                    val text = when (it) {
                        is AgentMessageInputContent.InputText -> it.text
                        is AgentMessageInputContent.EncryptedContent -> "[encrypted content]"
                    }
                    if (text.isNotBlank()) body(text, false)
                }
            }
        }
    }
}

@Composable
private fun Message(
    label: String,
    content: List<ContentItem>,
    supporting: Boolean,
    heading: @Composable (String) -> Unit,
    body: @Composable (String, Boolean) -> Unit,
) {
    heading(label)
    content.forEach {
        val text = when (it) {
            is ContentItem.InputText -> it.text
            is ContentItem.OutputText -> it.text
            is ContentItem.InputImage -> "Image: ${it.imageUrl.safeReference()} ${it.detail?.name?.lowercase() ?: "auto"}"
        }
        if (text.isNotBlank()) body(text, supporting)
    }
}

private fun String.safeReference(): String = when {
    startsWith("data:", ignoreCase = true) -> "[inline data, $length characters]"
    length > 160 -> take(157) + "..."
    else -> this
}
