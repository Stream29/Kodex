package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.app.ui.UiProse
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.app.ui.UiTextRole

/**
 * Shared message composition from its real typed event, without a flattened history cache.
 * Slots own only heading/body typography and measurement; supporting text marks developer content.
 * Inline media references are bounded, not decoded or fetched as arbitrary external URLs.
 */
@Composable
public expect fun HistoryMessageContent(
    event: AgentCommit.ConversationMessage,
    heading: @Composable (String) -> Unit = { UiText(it, UiTextRole.Title, maximumLines = 1) },
    body: @Composable (String, supporting: Boolean) -> Unit = { text, supporting ->
        UiProse(text, if (supporting) UiTextRole.Supporting else UiTextRole.Body)
    },
    assistantBody: @Composable (String) -> Unit = { text -> body(text, false) },
): Unit
