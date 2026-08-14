package io.github.stream29.kodex.desktop.application

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.app.agent.contract.AgentHistoryActionState
import io.github.stream29.kodex.app.agent.contract.AgentHistoryTarget
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.agent.contract.RequestUserInputState
import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.app.history.contract.AgentHistoryWindowSnapshot
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.settings.contract.SettingsPage
import io.github.stream29.kodex.desktop.agent.RequestUserInputDesktopPanel
import io.github.stream29.kodex.desktop.components.DesktopComposer
import io.github.stream29.kodex.desktop.components.DesktopComposerSubmitKey
import io.github.stream29.kodex.desktop.history.AgentHistoryDesktopView
import io.github.stream29.kodex.desktop.history.AgentHistoryDesktopUiState
import io.github.stream29.kodex.desktop.newsession.NewSessionDesktopView
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.ContentItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Canonical history/request/steer/composer/status stack for one Agent. */
@Composable
internal fun AgentRuntimeDesktopScreen(
    viewModel: AgentViewModel,
    session: PersistedSessionViewModel,
    application: ApplicationViewModel,
    historyUiState: AgentHistoryDesktopUiState,
    submitKey: DesktopComposerSubmitKey,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
): Unit {
    val execution by viewModel.execution.collectAsState()
    val stream by viewModel.stream.collectAsState()
    val composer by viewModel.composer.state.collectAsState()
    val requestUserInput by viewModel.requestUserInput.state.collectAsState()
    val notification by viewModel.notification.collectAsState()
    val scope = rememberCoroutineScope()

    notification?.let { value ->
        LaunchedEffect(viewModel, value.id) {
            snackbarHostState.showSnackbar(
                buildString {
                    append(value.message)
                    value.detail?.let { append("\n$it") }
                },
            )
            viewModel.dismissNotification(value.id)
        }
    }

    Column(modifier.fillMaxSize()) {
        AgentHistoryDesktopView(
            agentId = viewModel.address.agentId,
            viewModel = viewModel.history,
            streamState = stream,
            uiState = historyUiState,
            shellSessions = viewModel.shellSessions,
            canActOnHistory = !execution.running &&
                execution.capabilities.canReplaceHistory &&
                execution.capabilities.canForkHistory,
            onRequestRevert = viewModel::requestHistoryRevert,
            onRequestFork = { target ->
                scope.launch {
                    try {
                        val newSessionIndex = session.fork(viewModel, target)
                        application.openSession(newSessionIndex)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Throwable) {
                        snackbarHostState.showSnackbar(failure.frontendMessage())
                    }
                }
            },
            modifier = Modifier.weight(1f),
        )
        if (requestUserInput is RequestUserInputState.Pending) {
            RequestUserInputDesktopPanel(
                viewModel = viewModel.requestUserInput,
                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
            )
        }
        PendingSteerDesktopPreview(
            pending = stream.pendingSteer,
            modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        DesktopComposer(
            text = composer.text,
            cursorOffset = composer.cursorOffset,
            submitKey = submitKey,
            onValueChange = viewModel.composer::update,
            onSubmit = {
                scope.launch {
                    runCatching { viewModel.submitComposer(composer.revision) }
                        .onFailure {
                            snackbarHostState.showSnackbar(it.frontendMessage())
                        }
                }
            },
            enabled = requestUserInput !is RequestUserInputState.Pending,
            autoFocus = requestUserInput !is RequestUserInputState.Pending,
            supportingText = "Submit to steer".takeIf {
                execution.running && composer.text.isNotBlank()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        AgentRuntimeStatusBarDesktop(
            viewModel = viewModel,
            onBrowseWorkingDirectory = {
                scope.launch { application.openWorkingDirectoryPopup(viewModel) }
            },
            onOpenSettings = {
                scope.launch {
                    application.openSettingsPopup(session, SettingsPage.Session)
                }
            },
        )
    }

    AgentHistoryRevertDesktopDialog(viewModel)
}

/** Canonical blank-history/composer/status stack for a New Session tab. */
@Composable
internal fun NewSessionRuntimeDesktopScreen(
    viewModel: NewSessionViewModel,
    application: ApplicationViewModel,
    submitKey: DesktopComposerSubmitKey,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
): Unit {
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxSize()) {
        NewSessionDesktopView(
            viewModel = viewModel,
            submitKey = submitKey,
            onMaterialize = {
                val index = application.navigation.value.tabs.indexOfFirst { it === viewModel }
                if (index >= 0) {
                    scope.launch {
                        runCatching { application.materializeNewSession(index) }
                            .onFailure {
                                snackbarHostState.showSnackbar(it.frontendMessage())
                            }
                    }
                }
            },
            modifier = Modifier.weight(1f),
        )
        NewSessionStatusBarDesktop(
            viewModel = viewModel,
            onBrowseWorkingDirectory = {
                scope.launch { application.openWorkingDirectoryPopup(viewModel) }
            },
            onOpenSettings = {
                scope.launch {
                    application.openSettingsPopup(viewModel, SettingsPage.Session)
                }
            },
        )
    }
}

@Composable
private fun PendingSteerDesktopPreview(
    pending: List<StableCleanEvent.Steerable>,
    modifier: Modifier = Modifier,
): Unit {
    if (pending.isEmpty()) return
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "Pending steer (${pending.size})",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium,
        )
        pending.forEach { steer ->
            Text(
                text = "  ↳ ${steer.previewText()}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AgentHistoryRevertDesktopDialog(viewModel: AgentViewModel): Unit {
    val action by viewModel.historyAction.collectAsState()
    val confirm = action as? AgentHistoryActionState.ConfirmRevert ?: return
    val execution by viewModel.execution.collectAsState()
    val window by viewModel.history.window.collectAsState()
    val stillValid = !execution.running &&
        execution.capabilities.canReplaceHistory &&
        confirm.target.isCurrentIn(window)

    LaunchedEffect(viewModel, confirm.requestId, stillValid) {
        if (!stillValid) viewModel.dismissHistoryRevert(confirm.requestId)
    }
    if (!stillValid) return
    DisposableEffect(viewModel, confirm.requestId) {
        onDispose { viewModel.dismissHistoryRevert(confirm.requestId) }
    }
    AlertDialog(
        onDismissRequest = { viewModel.dismissHistoryRevert(confirm.requestId) },
        title = { Text("Revert history to here?") },
        text = {
            Text(
                "Keep the selected history entry and remove everything after it.\n" +
                    "This cannot be undone.",
            )
        },
        confirmButton = {
            Button(onClick = { viewModel.confirmHistoryRevert(confirm.requestId) }) {
                Text("Revert")
            }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.dismissHistoryRevert(confirm.requestId) }) {
                Text("Cancel")
            }
        },
    )
}

private fun StableCleanEvent.Steerable.previewText(): String = when (this) {
    is StableCleanEvent.UserMessage -> content.previewContentText()
    is StableCleanEvent.AssistantMessage -> "Assistant: ${content.previewContentText()}"
    is StableCleanEvent.DeveloperMessage -> "Developer: ${content.previewContentText()}"
    is StableCleanEvent.AgentMessage ->
        "$author → $recipient: ${content.previewAgentMessageText()}"
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

private fun AgentHistoryTarget.isCurrentIn(
    window: AgentHistoryWindowSnapshot,
): Boolean = generation == window.generation &&
    window.entries.any { it.key.primaryStorageIndex == storageIndex }

internal fun Throwable.frontendMessage(): String =
    message?.trim()?.takeIf(String::isNotEmpty) ?: toString()
