package io.github.stream29.kodex.cli.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
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
import io.github.stream29.kodex.cli.app.ComposerInput
import io.github.stream29.kodex.cli.components.TextInputLayout
import io.github.stream29.kodex.cli.components.TextInputState
import io.github.stream29.kodex.cli.components.TextInputValue
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.ellipsizeToTerminalWidth
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.ContentItem
import kotlinx.coroutines.launch

/**
 * Renders one Composer child. All draft and command state comes from [viewModel]; this renderer
 * keeps only Mosaic's widget buffer, focus, cursor, viewport and layout.
 *
 * Empty/ready/running/steer/request-input/error/closed branches are explicit in
 * [ComposerState.mode].
 * The Enter handler captures the displayed revision and invokes the component's revision-bound
 * submit command; it does not append messages, steer, clear drafts or inspect a sibling child.
 */
@Composable
public fun ComposerView(
    viewModel: ComposerViewModel,
    columns: Int,
    rows: Int,
    newLineKey: NewLineKey,
    autoFocus: Boolean = true,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    showAuxiliary: Boolean = true,
) {
    val state by viewModel.state.collectAsState()
    if (state.mode == ComposerMode.Closed || rows <= 0) return

    val input = remember(viewModel, state.ownerId) {
        TextInputState(TextInputValue(text = state.text, cursorOffset = state.cursorOffset))
    }
    LaunchedEffect(viewModel, state.ownerId, state.text, state.cursorOffset) {
        if (input.value.text != state.text || input.value.cursorOffset != state.cursorOffset) {
            input.reset(TextInputValue(text = state.text, cursorOffset = state.cursorOffset))
        }
    }

    val width = columns.coerceAtLeast(1)
    val previewLines = if (showAuxiliary && state.mode == ComposerMode.Steer) {
        pendingSteerPreviewLines(
            pending = state.pendingSteer,
            columns = width,
            maximumRows = minOf(PendingSteerMaximumRows, (rows - 1).coerceAtLeast(0)),
        )
    } else {
        emptyList()
    }
    val statusLine = composerStatusLine(state).takeIf { showAuxiliary }
    val auxiliaryRows = previewLines.size + if (statusLine == null) 0 else 1
    val composerAvailableRows = (rows - auxiliaryRows).coerceAtLeast(0)
    val fullLayout = TextInputLayout.create(
        value = input.value,
        width = width,
        firstLinePrefix = "> ",
        continuationLinePrefix = "  ",
        softWrap = true,
    )
    val composerRows = boundedComposerRows(
        availableRows = composerAvailableRows,
        desiredRows = fullLayout.rowCount,
    )
    val layout = fullLayout.withViewportRows(composerRows)
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.width(width).height(rows)) {
        previewLines.forEachIndexed { index, line ->
            Text(line, textStyle = if (index == 0) TextStyle.Bold else TextStyle.Dim)
        }
        statusLine?.let { line ->
            Text(line, textStyle = if (state.mode == ComposerMode.Error) {
                TextStyle.Dim
            } else {
                TuiTheme.typography.supporting
            })
        }
        ComposerInput(
            state = input,
            layout = layout,
            newLineKey = newLineKey,
            autoFocus = autoFocus && state.mode != ComposerMode.RequestInput,
            enabled = enabled &&
                state.mode != ComposerMode.RequestInput &&
                state.submission !is ComposerSubmissionState.Submitting,
            focusRequester = focusRequester,
            onSubmit = {
                val expectedRevision = state.revision
                scope.launch { viewModel.submit(expectedRevision) }
            },
            onValueChanged = { value -> viewModel.update(value.text, value.cursorOffset) },
        )
    }
}

/** Renderer-local status copy; it is not a second submission/failure authority. */
internal fun composerStatusLine(state: ComposerState): String? = when {
    state.mode == ComposerMode.Error -> {
        val failure = (state.submission as? ComposerSubmissionState.Failed)?.failure
        failure?.let { "Unable to submit: ${it.message}" }
    }

    state.mode == ComposerMode.RequestInput -> {
        val pending = state.requestInput as ComposerRequestInputPresentation.Pending
        "Input requested: ${pending.title} (${pending.questionCount})"
    }

    state.submission is ComposerSubmissionState.Submitting -> "Submitting…"
    state.mode == ComposerMode.Running || state.mode == ComposerMode.Steer ->
        submitToSteerHint(state.running, state.text)
    else -> null
}

internal fun submitToSteerHint(running: Boolean, draft: String): String? =
    "Submit to steer".takeIf { running && draft.isNotBlank() }

internal fun boundedComposerRows(availableRows: Int, desiredRows: Int): Int {
    require(desiredRows > 0) { "Composer content must contain at least one row." }
    return minOf(desiredRows, availableRows.coerceAtLeast(1))
}

internal fun pendingSteerPreviewLines(
    pending: List<StableIndexEvent.Steerable>,
    columns: Int,
    maximumRows: Int,
): List<String> {
    if (pending.isEmpty() || maximumRows <= 0) return emptyList()
    val width = columns.coerceAtLeast(1)
    val header = "Pending steer (${pending.size})".ellipsizeToTerminalWidth(width)
    if (maximumRows == 1) return listOf(header)
    val detailCapacity = maximumRows - 1
    val visibleCount =
        if (pending.size > detailCapacity && detailCapacity > 1) detailCapacity - 1 else detailCapacity
    return buildList {
        add(header)
        pending.take(visibleCount).forEach { steer ->
            add(("  ↳ " + steer.previewText()).ellipsizeToTerminalWidth(width))
        }
        if (pending.size > visibleCount && detailCapacity > 1) {
            add("  … ${pending.size - visibleCount} more".ellipsizeToTerminalWidth(width))
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

private const val PendingSteerMaximumRows: Int = 6
