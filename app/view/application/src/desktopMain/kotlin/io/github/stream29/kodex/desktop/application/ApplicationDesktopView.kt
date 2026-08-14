package io.github.stream29.kodex.desktop.application

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionTopologyState
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.session.contract.SessionViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.desktop.components.DesktopComposerSubmitKey
import io.github.stream29.kodex.desktop.components.DesktopMessage
import io.github.stream29.kodex.desktop.components.DesktopModal
import io.github.stream29.kodex.desktop.components.desktopSecondaryClick
import io.github.stream29.kodex.desktop.pathpicker.DirectoryPickerDesktopDialog
import io.github.stream29.kodex.desktop.settings.OpenAiLoginDesktopDialog
import io.github.stream29.kodex.desktop.settings.SettingsDesktopDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant

/** Material 3 Expressive renderer of the existing TUI application hierarchy. */
@Composable
public fun ApplicationDesktopView(
    viewModel: ApplicationViewModel,
    newLineKey: StateFlow<NewLineKey>,
    modifier: Modifier = Modifier,
): Unit {
    val navigation by viewModel.navigation.collectAsState()
    val popup by viewModel.popup.collectAsState()
    val currentNewLineKey by newLineKey.collectAsState()
    val historyStates = rememberAgentHistoryDesktopUiStateRegistry(navigation.tabs)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var sidebarPinnedExpanded by remember { mutableStateOf(false) }
    var sidebarHovered by remember { mutableStateOf(false) }
    var sidebarContextMenuOpen by remember { mutableStateOf(false) }
    val sidebarExpanded =
        sidebarPinnedExpanded || sidebarHovered || sidebarContextMenuOpen
    val sidebarWidth by animateDpAsState(
        targetValue = if (sidebarExpanded) {
            SessionSidebarExpandedWidth
        } else {
            SessionSidebarCollapsedWidth
        },
        label = "agent sidebar width",
    )

    val selected = navigation.selected
    val selectedPersisted = selected as? PersistedSessionViewModel
    val selectedAgent = collectSelectedAgent(selectedPersisted)
    val topology = collectTopology(selectedPersisted)
    PersistedSessionNotificationEffect(selectedPersisted, snackbarHostState)

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                SessionTabBarDesktop(
                    navigation = navigation,
                    onSelect = { index -> scope.launch { viewModel.selectTab(index) } },
                    onCreate = { scope.launch { viewModel.createNewSessionTab() } },
                    onOpenCatalog = {
                        scope.launch { viewModel.openSessionCatalogPopup() }
                    },
                    onClose = { target -> scope.launch { viewModel.closeTab(target) } },
                    onRename = { target ->
                        scope.launch { viewModel.openRenameSessionPopup(target) }
                    },
                    onDelete = { target ->
                        scope.launch {
                            viewModel.openDeleteSessionPopup(target.sessionIndex)
                        }
                    },
                )
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    SessionAgentSidebarDesktop(
                        topology = topology,
                        selectedAgent = selectedAgent,
                        expanded = sidebarExpanded,
                        onHoverChanged = { sidebarHovered = it },
                        onToggleExpanded = {
                            sidebarPinnedExpanded = !sidebarPinnedExpanded
                            if (!sidebarPinnedExpanded) sidebarContextMenuOpen = false
                        },
                        onExpandAgent = { address ->
                            selectedPersisted?.let { session ->
                                scope.launch {
                                    session.materializeDirectChildren(address)
                                }
                            }
                        },
                        onSelectAgent = { address ->
                            selectedPersisted?.let { session ->
                                scope.launch {
                                    runCatching { session.selectAgent(address) }
                                        .onFailure {
                                            snackbarHostState.showSnackbar(
                                                it.frontendMessage(),
                                            )
                                        }
                                }
                            }
                        },
                        onContextMenuVisibilityChanged = {
                            sidebarContextMenuOpen = it
                        },
                        modifier = Modifier.width(sidebarWidth),
                    )
                    Box(Modifier.weight(1f).fillMaxSize()) {
                        when (selected) {
                            is NewSessionViewModel -> NewSessionRuntimeDesktopScreen(
                                viewModel = selected,
                                application = viewModel,
                                submitKey = currentNewLineKey.desktopSubmitKey(),
                                snackbarHostState = snackbarHostState,
                                modifier = Modifier.fillMaxSize(),
                            )

                            is PersistedSessionViewModel -> selectedAgent?.let { agent ->
                                key(agent) {
                                    AgentRuntimeDesktopScreen(
                                        viewModel = agent,
                                        session = selected,
                                        application = viewModel,
                                        historyUiState = historyStates.stateFor(
                                            selected.sessionIndex,
                                            agent.address.agentId,
                                        ),
                                        submitKey = currentNewLineKey.desktopSubmitKey(),
                                        snackbarHostState = snackbarHostState,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            )
        }
    }

    ApplicationDesktopPopup(viewModel, popup)
}

@Composable
private fun collectSelectedAgent(
    session: PersistedSessionViewModel?,
): AgentViewModel? {
    if (session == null) return null
    return key(session) {
        val selected by session.selectedAgent.collectAsState()
        selected
    }
}

@Composable
private fun collectTopology(
    session: PersistedSessionViewModel?,
): PersistedSessionTopologyState? {
    if (session == null) return null
    return key(session) {
        val topology by session.topology.collectAsState()
        topology
    }
}

@Composable
private fun PersistedSessionNotificationEffect(
    session: PersistedSessionViewModel?,
    snackbarHostState: SnackbarHostState,
): Unit {
    if (session == null) return
    key(session) {
        val notification by session.notification.collectAsState()
        notification?.let { value ->
            LaunchedEffect(session, value.id) {
                snackbarHostState.showSnackbar(
                    buildString {
                        append(value.message)
                        value.detail?.let { append("\n$it") }
                    },
                )
                session.dismissNotification(value.id)
            }
        }
    }
}

@Composable
private fun ApplicationDesktopPopup(
    application: ApplicationViewModel,
    popup: ApplicationPopupState,
): Unit {
    val scope = rememberCoroutineScope()
    when (popup) {
        ApplicationPopupState.Closed -> Unit
        is ApplicationPopupState.SessionCatalog -> SessionCatalogDesktopDialog(
            popup = popup,
            onDismissRequest = { application.dismissPopup(popup) },
            onOpen = { index ->
                scope.launch {
                    application.openSession(index)
                    application.dismissPopup(popup)
                }
            },
            onDelete = { index ->
                scope.launch { application.openDeleteSessionPopup(index) }
            },
        )

        is ApplicationPopupState.Settings -> SettingsDesktopDialog(
            viewModel = popup.viewModel,
            onDismissRequest = { application.dismissPopup(popup) },
            onOpenLogin = { scope.launch { application.openLoginPopup() } },
        )

        is ApplicationPopupState.RenameSession -> RenameSessionDesktopDialog(
            popup = popup,
            onDismissRequest = { application.dismissPopup(popup) },
            onRenamed = {
                scope.launch {
                    popup.viewModel.rename()
                    application.dismissPopup(popup)
                }
            },
        )

        is ApplicationPopupState.DeleteSession -> DeleteSessionDesktopDialog(
            popup = popup,
            onDismissRequest = { application.dismissPopup(popup) },
            onDelete = {
                scope.launch {
                    popup.viewModel.delete()
                    application.dismissPopup(popup)
                }
            },
        )

        is ApplicationPopupState.Login -> OpenAiLoginDesktopDialog(
            viewModel = popup.viewModel,
            onDismissRequest = { application.dismissPopup(popup) },
        )

        is ApplicationPopupState.WorkingDirectory -> DirectoryPickerDesktopDialog(
            viewModel = popup.viewModel.picker,
            onDismissRequest = { application.dismissPopup(popup) },
            onDirectorySelected = { directory ->
                scope.launch {
                    popup.viewModel.select(directory)
                    application.dismissPopup(popup)
                }
            },
        )
    }
}

@Composable
private fun SessionCatalogDesktopDialog(
    popup: ApplicationPopupState.SessionCatalog,
    onDismissRequest: () -> Unit,
    onOpen: (Int) -> Unit,
    onDelete: (Int) -> Unit,
): Unit {
    val sessions by popup.viewModel.sessions.collectAsState()
    var failure by remember(popup) { mutableStateOf<String?>(null) }
    LaunchedEffect(popup) {
        try {
            popup.viewModel.refresh()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            failure = error.frontendMessage()
        }
    }
    DesktopModal(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.width(640.dp).heightIn(min = 360.dp, max = 680.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RectangleShape,
                ) {
                    Text(
                        text = "Sessions",
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    failure?.let {
                        DesktopMessage(
                            title = "Unable to load sessions",
                            detail = it,
                        )
                    } ?: if (sessions.isEmpty()) {
                        Text(
                            text = "No persisted sessions",
                            modifier = Modifier.padding(14.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(
                                items = sessions,
                                key = SessionCatalogEntry::sessionIndex,
                            ) { entry ->
                                SessionCatalogDesktopRow(
                                    entry = entry,
                                    onOpen = onOpen,
                                    onDelete = onDelete,
                                )
                            }
                        }
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RectangleShape,
                ) {
                    TextButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCatalogDesktopRow(
    entry: SessionCatalogEntry,
    onOpen: (Int) -> Unit,
    onDelete: (Int) -> Unit,
): Unit {
    var menuOpen by remember(entry.sessionIndex) { mutableStateOf(false) }
    Box {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .desktopSecondaryClick { menuOpen = true }
                .clickable { onOpen(entry.sessionIndex) },
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RectangleShape,
        ) {
            Text(
                text = entry.desktopCatalogLabel(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = {
                    menuOpen = false
                    onDelete(entry.sessionIndex)
                },
            )
        }
    }
}

@Composable
private fun RenameSessionDesktopDialog(
    popup: ApplicationPopupState.RenameSession,
    onDismissRequest: () -> Unit,
    onRenamed: () -> Unit,
): Unit {
    val draft by popup.viewModel.draftName.collectAsState()
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Rename session") },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = popup.viewModel::updateDraftName,
                modifier = Modifier.onPreviewKeyEvent { event ->
                    if (
                        event.type == KeyEventType.KeyDown &&
                        event.key == Key.Enter &&
                        !event.isShiftPressed &&
                        !event.isCtrlPressed &&
                        !event.isAltPressed &&
                        draft.isNotBlank()
                    ) {
                        onRenamed()
                        true
                    } else {
                        false
                    }
                },
                singleLine = true,
                prefix = { Text(">") },
            )
        },
        confirmButton = {
            Button(onClick = onRenamed, enabled = draft.isNotBlank()) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text("Cancel") }
        },
    )
}

@Composable
private fun DeleteSessionDesktopDialog(
    popup: ApplicationPopupState.DeleteSession,
    onDismissRequest: () -> Unit,
    onDelete: () -> Unit,
): Unit {
    val title = popup.viewModel.threadName ?: "Session ${popup.viewModel.sessionIndex}"
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Delete $title?") },
        text = { Text("This removes the persisted session.") },
        confirmButton = {
            Button(onClick = onDelete) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text("Cancel") }
        },
    )
}

private fun NewLineKey.desktopSubmitKey(): DesktopComposerSubmitKey = when (this) {
    NewLineKey.ShiftEnter -> DesktopComposerSubmitKey.Enter
    NewLineKey.Enter -> DesktopComposerSubmitKey.CtrlEnter
}

private fun SessionCatalogEntry.desktopCatalogLabel(
    now: Instant = Clock.System.now(),
): String {
    val title = threadName ?: "Session $sessionIndex"
    val lastActivity = lastActivityAt?.relativeTimeFrom(now) ?: return title
    return "$title · $lastActivity"
}

private fun Instant.relativeTimeFrom(now: Instant): String {
    val seconds = (now - this).inWholeSeconds.coerceAtLeast(0L)
    return when {
        seconds < 60L -> "now"
        seconds < 60L * 60L -> "${seconds / 60L}m ago"
        seconds < 24L * 60L * 60L -> "${seconds / (60L * 60L)}h ago"
        else -> "${seconds / (24L * 60L * 60L)}d ago"
    }
}
