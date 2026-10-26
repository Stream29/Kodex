package io.github.stream29.kodex.cli.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAgentMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableDeveloperMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.app.agent.contract.ComposerMode
import io.github.stream29.kodex.app.agent.contract.ComposerRequestInputPresentation
import io.github.stream29.kodex.app.agent.contract.ComposerState
import io.github.stream29.kodex.app.agent.contract.ComposerSubmissionState
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.app.ui.UiColumn
import io.github.stream29.kodex.app.ui.UiEditor
import io.github.stream29.kodex.app.ui.UiSubmitKey
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.app.ui.UiTextRole
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.cli.settings.SubmitKey
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.ContentItem
import kotlinx.coroutines.launch

@Composable
public actual fun ComposerContent(
    viewModel: ComposerViewModel,
    newLineKey: NewLineKey,
    autoFocus: Boolean,
    enabled: Boolean,
    showPendingSteer: Boolean,
    showFailure: Boolean,
    maximumPreviewLines: Int,
    maximumEditorLines: Int,
    frame: @Composable (content: @Composable () -> Unit) -> Unit,
) {
    require(maximumEditorLines > 0)
    val state by viewModel.state.collectAsState()
    if (state.mode == ComposerMode.Closed) return

    val previewLines = if (showPendingSteer && state.mode == ComposerMode.Steer) {
        pendingSteerPreviewContent(state.pendingSteer, maximumPreviewLines)
    } else {
        emptyList()
    }
    val statusLine = composerStatusLine(state, showFailure)
    val scope = rememberCoroutineScope()

    frame {
        UiColumn {
            previewLines.forEachIndexed { index, line ->
                UiText(line, if (index == 0) UiTextRole.Title else UiTextRole.Supporting, maximumLines = 1)
            }
            statusLine?.let { UiText(it, UiTextRole.Supporting, maximumLines = 1) }
            key(viewModel, state.ownerId) {
                UiEditor(
                    text = state.text,
                    cursorOffset = state.cursorOffset,
                    revision = state.revision,
                    maximumLines = maximumEditorLines,
                    submitKey = if (newLineKey.submitKey == SubmitKey.Enter) UiSubmitKey.Enter else UiSubmitKey.CtrlEnter,
                    autoFocus = autoFocus && state.mode != ComposerMode.RequestInput,
                    enabled = enabled && state.mode != ComposerMode.RequestInput,
                    readOnly = state.submission is ComposerSubmissionState.Submitting,
                    onSubmit = { _, _ ->
                        val expectedRevision = state.revision
                        scope.launch { viewModel.submit(expectedRevision) }
                    },
                    onChange = { text, cursor -> viewModel.update(text, cursor) },
                )
            }
        }
    }
}

public actual fun composerStatusLine(state: ComposerState, showFailure: Boolean): String? = when {
    state.mode == ComposerMode.Closed -> null
    state.mode == ComposerMode.Error && showFailure -> {
        val failure = (state.submission as? ComposerSubmissionState.Failed)?.failure
        failure?.let { "Unable to submit: ${it.message}" }
    }

    state.mode == ComposerMode.RequestInput -> {
        val pending = state.requestInput as ComposerRequestInputPresentation.Pending
        "Input requested: ${pending.title} (${pending.questionCount})"
    }

    state.submission is ComposerSubmissionState.Submitting -> "Submitting…"
    state.running ->
        submitToSteerHint(state.running, state.text)
    else -> null
}

private fun submitToSteerHint(running: Boolean, draft: String): String? =
    "Submit to steer".takeIf { running && draft.isNotBlank() }

public actual fun pendingSteerPreviewContent(
    pending: List<StableIndexEvent.Steerable>,
    maximumRows: Int,
): List<String> {
    if (pending.isEmpty() || maximumRows <= 0) return emptyList()
    val header = "Pending steer (${pending.size})"
    if (maximumRows == 1) return listOf(header)
    val detailCapacity = maximumRows - 1
    val visibleCount =
        if (pending.size > detailCapacity && detailCapacity > 1) detailCapacity - 1 else detailCapacity
    return buildList {
        add(header)
        pending.take(visibleCount).forEach { steer ->
            add("  ↳ " + steer.previewText())
        }
        if (pending.size > visibleCount && detailCapacity > 1) {
            add("  … ${pending.size - visibleCount} more")
        }
    }
}

private fun StableIndexEvent.Steerable.previewText(): String = when (this) {
    is StableUserMessage -> content.previewContentText()
    is StableAssistantMessage -> "Assistant: ${content.previewContentText()}"
    is StableDeveloperMessage -> "Developer: ${content.previewContentText()}"
    is StableAgentMessage -> "$author → $recipient: ${content.previewAgentMessageText()}"
}.ifBlank { "[empty message]" }

private fun List<ContentItem>.previewContentText(): String =
    joinToString(" ") { part ->
        when (part) {
            is ContentItem.InputText -> part.text.singleLine()
            is ContentItem.OutputText -> part.text.singleLine()
            is ContentItem.InputImage -> "[image]"
        }
    }.trim()

private fun List<AgentMessageInputContent>.previewAgentMessageText(): String =
    joinToString(" ") { part ->
        when (part) {
            is AgentMessageInputContent.InputText -> part.text.singleLine()
            is AgentMessageInputContent.EncryptedContent -> "[encrypted content]"
        }
    }.trim()

private fun String.singleLine(): String =
    lineSequence().joinToString(" ") { line -> line.trim() }.trim()
