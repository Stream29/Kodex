package io.github.stream29.kodex.desktop.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.settings.contract.GlobalSettingsEffect
import io.github.stream29.kodex.app.settings.contract.GlobalSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsState
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsStatus
import io.github.stream29.kodex.app.settings.contract.NewSessionSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.SessionSettingsEffect
import io.github.stream29.kodex.app.settings.contract.SessionSettingsSnapshot
import io.github.stream29.kodex.app.settings.contract.SessionSettingsState
import io.github.stream29.kodex.app.settings.contract.SessionSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.app.settings.contract.SettingsPage
import io.github.stream29.kodex.app.settings.contract.SettingsViewModel
import io.github.stream29.kodex.app.settings.contract.UsageResetOption
import io.github.stream29.kodex.app.settings.contract.UsageResetState
import io.github.stream29.kodex.app.settings.contract.snapshotOrNull
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.cli.settings.SubmitKey
import io.github.stream29.kodex.desktop.components.DesktopChoice
import io.github.stream29.kodex.desktop.components.DesktopChoiceGroup
import io.github.stream29.kodex.desktop.components.DesktopModal
import io.github.stream29.kodex.desktop.components.DesktopSection
import io.github.stream29.kodex.desktop.pathpicker.DirectoryPickerDesktopDialog
import io.github.stream29.kodex.mcp.contract.McpClientFailureReason
import io.github.stream29.kodex.openai.ModeKind
import io.github.stream29.kodex.openai.OpenAiAuthState
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.openai.accountusage.CodexAccountRateLimit
import io.github.stream29.kodex.openai.accountusage.CodexAccountRateLimitWindow
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageSection
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageSnapshot
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetOutcome
import kotlinx.coroutines.flow.collect

/** Material Desktop renderer for one independently owned Settings hierarchy. */
@Composable
public fun SettingsDesktopDialog(
    viewModel: SettingsViewModel,
    onDismissRequest: () -> Unit,
    onOpenLogin: () -> Unit,
): Unit {
    val selectedPage by viewModel.selectedPage.collectAsState()
    val currentOnOpenLogin by rememberUpdatedState(onOpenLogin)
    var renameRequest by remember(viewModel) {
        mutableStateOf<SessionSettingsEffect.RenameSession?>(null)
    }

    DisposableEffect(viewModel) {
        onDispose(viewModel::close)
    }
    LaunchedEffect(viewModel.global) {
        viewModel.global.effects.collect { effect ->
            when (effect) {
                GlobalSettingsEffect.OpenLogin -> currentOnOpenLogin()
            }
        }
    }
    LaunchedEffect(viewModel.session) {
        viewModel.session.effects.collect { effect ->
            when (effect) {
                is SessionSettingsEffect.RenameSession -> renameRequest = effect
            }
        }
    }
    LaunchedEffect(selectedPage) {
        renameRequest = null
    }

    DesktopModal(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.width(900.dp).heightIn(min = 600.dp, max = 820.dp),
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
                        text = "Settings",
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    SettingsNavigation(
                        selectedPage = selectedPage,
                        onSelect = viewModel::selectPage,
                    )
                    SettingsPageContent(
                        viewModel = viewModel,
                        page = selectedPage,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
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

    renameRequest?.let { request ->
        RenameSessionDesktopDialog(
            request = request,
            onDismissRequest = { renameRequest = null },
            onRename = { name ->
                renameRequest = null
                viewModel.session.renameSession(request.expectedRevision, name)
            },
        )
    }

    val directoryPicker by viewModel.session.directoryPicker.collectAsState()
    directoryPicker?.let { picker ->
        DirectoryPickerDesktopDialog(
            viewModel = picker.viewModel,
            onDismissRequest = {
                viewModel.session.dismissWorkingDirectoryPicker(picker)
            },
            onDirectorySelected = { directory ->
                viewModel.session.selectWorkingDirectory(picker, directory)
            },
        )
    }
}

@Composable
private fun SettingsNavigation(
    selectedPage: SettingsPage,
    onSelect: (SettingsPage) -> Unit,
): Unit {
    Surface(
        modifier = Modifier.width(180.dp).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RectangleShape,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SettingsPage.entries.forEach { page ->
                val selected = page == selectedPage
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(page) },
                    color = if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    contentColor = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    shape = RectangleShape,
                ) {
                    Text(
                        page.desktopLabel(),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsPageContent(
    viewModel: SettingsViewModel,
    page: SettingsPage,
    modifier: Modifier,
): Unit {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState()),
    ) {
        when (page) {
            SettingsPage.Global -> GlobalSettingsContent(viewModel.global)
            SettingsPage.Session -> SessionSettingsContent(viewModel.session)
            SettingsPage.NewSession -> NewSessionSettingsContent(viewModel.newSession)
        }
    }
}

@Composable
private fun GlobalSettingsContent(viewModel: GlobalSettingsViewModel): Unit {
    val state by viewModel.state.collectAsState()
    val authentication by viewModel.authentication.collectAsState()
    val accountUsage by viewModel.accountUsage.collectAsState()
    val mcpServers by viewModel.mcpServers.collectAsState()
    val usageReset by viewModel.usageReset.collectAsState()

    DesktopSection(
        title = "Kodex home",
    ) {
        Text(
            state.codexHome.toString(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    McpSettingsContent(
        servers = mcpServers,
        onReconnect = viewModel::reconnectMcpServer,
    )

    DesktopSection(
        title = "Authentication",
    ) {
        DesktopChoiceGroup(
            selected = state.authSource,
            options = KodexAuthSource.entries.toList(),
            optionLabel = KodexAuthSource::desktopLabel,
            onSelect = viewModel::updateAuthSource,
        )
    }

    DesktopSection(
        title = "Automatic session title",
    ) {
        DesktopChoiceGroup(
            selected = state.sessionTitle.enabled,
            options = listOf(true, false),
            optionLabel = { enabled -> if (enabled) "Enabled" else "Disabled" },
            onSelect = viewModel::updateSessionTitleEnabled,
        )
    }

    DesktopSection(
        title = "Title model",
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        DesktopChoice(
            label = "",
            selected = state.effectiveSessionTitleModel,
            options = state.modelOptions,
            optionLabel = OpenAiModelId::value,
            onSelect = viewModel::updateSessionTitleModel,
        )
    }

    DesktopSection(
        title = "Title reasoning",
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        DesktopChoice(
            label = "",
            selected = state.sessionTitle.reasoningEffort,
            options = knownReasoningEfforts,
            optionLabel = ReasoningEffort::desktopLabel,
            onSelect = viewModel::updateSessionTitleReasoningEffort,
        )
    }

    AuthenticationSettingsContent(
        state = authentication,
        onLogin = viewModel::requestLogin,
    )
    AccountUsageSettingsContent(
        state = accountUsage,
        onRefresh = viewModel::refreshUsage,
        onRequestReset = viewModel::requestUsageReset,
    )
    Row(Modifier.fillMaxWidth()) {
        DesktopSection(
            title = "New line key",
            modifier = Modifier.weight(1f),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            DesktopChoiceGroup(
                selected = state.newLineKey,
                options = NewLineKey.entries.toList(),
                optionLabel = NewLineKey::desktopLabel,
                onSelect = viewModel::updateNewLineKey,
            )
        }
        DesktopSection(
            title = "Submit key",
            modifier = Modifier.weight(1f),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            DesktopChoiceGroup(
                selected = state.newLineKey.submitKey,
                options = SubmitKey.entries.toList(),
                optionLabel = SubmitKey::desktopLabel,
                onSelect = { key -> viewModel.updateNewLineKey(key.newLineKey) },
            )
        }
    }
    UsageResetDialogHost(
        state = usageReset,
        viewModel = viewModel,
    )
}

@Composable
private fun AuthenticationSettingsContent(
    state: SettingsAuthenticationState,
    onLogin: () -> Unit,
): Unit {
    DesktopSection(title = "OpenAI account") {
        when (state) {
            is SettingsAuthenticationState.Authenticated -> {
                val identity = state.email
                    ?.let { "Signed in as $it" }
                    ?: state.accountId
                        ?.let { "Signed in as account $it" }
                        ?: "Signed in"
                Text(identity)
                state.planType?.let { plan ->
                    Text(
                        "Plan: ${plan.rawValue}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is SettingsAuthenticationState.Unavailable -> {
                Text("Authentication unavailable")
                Text(
                    state.reason.desktopDescription(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onLogin) {
                    Text("Sign in")
                }
            }
        }
    }
}

@Composable
private fun AccountUsageSettingsContent(
    state: SettingsAccountUsageState,
    onRefresh: () -> Unit,
    onRequestReset: () -> Unit,
): Unit {
    val snapshot = state.snapshotOrNull()
    DesktopSection(title = "Codex usage") {
        when {
            snapshot != null -> {
                if (snapshot.rateLimits.isEmpty()) {
                    Text("Rate limits unavailable")
                } else {
                    snapshot.rateLimits.forEach { limit ->
                        Text(limit.desktopDisplayLine())
                    }
                }
                Text(
                    snapshot.tokenUsage?.lifetimeTokens?.let {
                        "Lifetime tokens: ${it.groupedDecimal()}"
                    } ?: "Lifetime tokens: unavailable",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    snapshot.resetCredits.availableCount?.let {
                        "Usage limit resets: ${it.groupedDecimal()} available"
                    } ?: "Usage limit resets: unavailable",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (
                    snapshot.resetCredits.availableCount?.let { it > 0L } == true &&
                    CodexAccountUsageSection.ResetCreditDetails in snapshot.unavailableSections
                ) {
                    Text(
                        "Reset details unavailable; the backend will choose a reset.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (CodexAccountUsageSection.TokenUsage in snapshot.unavailableSections) {
                    Text(
                        "Token activity details unavailable.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state is SettingsAccountUsageState.Unavailable ->
                Text("Sign in to view Codex usage.")

            state is SettingsAccountUsageState.Loading ->
                Text("Loading usage…")

            state is SettingsAccountUsageState.Failed ->
                Text(state.message, color = MaterialTheme.colorScheme.error)

            state is SettingsAccountUsageState.Redeeming -> Unit
        }

        when (state) {
            is SettingsAccountUsageState.Loading -> if (snapshot != null) {
                Text(
                    "Refreshing usage…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is SettingsAccountUsageState.Failed -> if (snapshot != null) {
                Text(
                    state.message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is SettingsAccountUsageState.Redeeming -> {
                Text(
                    "Using a reset…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is SettingsAccountUsageState.Available,
            SettingsAccountUsageState.Unavailable,
                -> Unit
        }

        if (state !is SettingsAccountUsageState.Unavailable) {
            val busy = state is SettingsAccountUsageState.Loading ||
                state is SettingsAccountUsageState.Redeeming
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onRefresh, enabled = !busy) {
                    Text("Refresh")
                }
                Button(
                    onClick = onRequestReset,
                    enabled = state is SettingsAccountUsageState.Available &&
                        snapshot?.hasAvailableUsageReset() == true,
                ) {
                    Text("Use reset")
                }
            }
        }
    }
}

@Composable
private fun McpSettingsContent(
    servers: List<McpServerSettingsState>,
    onReconnect: (String) -> Unit,
): Unit {
    DesktopSection(title = "MCP servers") {
        if (servers.isEmpty()) {
            Text(
                "None configured",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            servers.forEach { server ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${server.serverName}: ${server.status.desktopLabel()}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (server.status is McpServerSettingsStatus.Failed) {
                        OutlinedButton(onClick = { onReconnect(server.serverName) }) {
                            Text("Reconnect")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionSettingsContent(viewModel: SessionSettingsViewModel): Unit {
    val state by viewModel.state.collectAsState()
    when (val current = state) {
        SessionSettingsState.Unavailable -> Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RectangleShape,
        ) {
            Text(
                text = "No selected session",
                modifier = Modifier.padding(16.dp),
            )
        }

        is SessionSettingsState.Available -> {
            val snapshot = current.snapshot
            DesktopSection(
                title = "Session name",
            ) {
                Text(snapshot.sessionName)
                OutlinedButton(
                    onClick = { viewModel.requestRename(snapshot.revision) },
                ) {
                    Text("Rename")
                }
            }
            DesktopSection(title = "Working directory") {
                Text(
                    snapshot.configuration.workingDirectory.toString(),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedButton(
                    onClick = { viewModel.requestWorkingDirectory(snapshot.revision) },
                    enabled = snapshot.editable,
                ) {
                    Text("Browse")
                }
            }
            SessionConfigurationContent(
                viewModel = viewModel,
                snapshot = snapshot,
                modelOptions = current.modelOptions,
            )
        }
    }
}

@Composable
private fun SessionConfigurationContent(
    viewModel: SessionSettingsViewModel,
    snapshot: SessionSettingsSnapshot,
    modelOptions: List<OpenAiModelId>,
): Unit {
    val configuration = snapshot.configuration
    DesktopSection(
        title = "Model",
    ) {
        DesktopChoice(
            label = "",
            selected = configuration.model,
            options = modelOptions,
            optionLabel = OpenAiModelId::value,
            enabled = snapshot.editable,
            onSelect = { viewModel.updateModel(snapshot.revision, it) },
        )
    }
    DesktopSection(
        title = "Reasoning",
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        DesktopChoice(
            label = "",
            selected = configuration.reasoningEffort,
            options = knownReasoningEfforts,
            optionLabel = ReasoningEffort::desktopLabel,
            enabled = snapshot.editable,
            onSelect = { viewModel.updateReasoningEffort(snapshot.revision, it) },
        )
    }
    DesktopSection(
        title = "Service tier",
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        DesktopChoice(
            label = "",
            selected = configuration.serviceTier,
            options = ServiceTier.entries.toList(),
            optionLabel = ServiceTier::desktopLabel,
            enabled = snapshot.editable,
            onSelect = { viewModel.updateServiceTier(snapshot.revision, it) },
        )
    }
    DesktopSection(
        title = "Mode",
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        DesktopChoice(
            label = "",
            selected = configuration.mode,
            options = ModeKind.entries.toList(),
            optionLabel = ModeKind::desktopLabel,
            enabled = snapshot.editable,
            onSelect = { viewModel.updateMode(snapshot.revision, it) },
        )
    }
}

@Composable
private fun NewSessionSettingsContent(viewModel: NewSessionSettingsViewModel): Unit {
    val state by viewModel.state.collectAsState()
    val settings = state.settings
    DesktopSection(
        title = "Model",
    ) {
        DesktopChoice(
            label = "",
            selected = settings.model,
            options = state.modelOptions,
            optionLabel = OpenAiModelId::value,
            onSelect = { viewModel.updateModel(state.revision, it) },
        )
    }
    DesktopSection(
        title = "Reasoning",
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        DesktopChoice(
            label = "",
            selected = settings.reasoningEffort,
            options = knownReasoningEfforts,
            optionLabel = ReasoningEffort::desktopLabel,
            onSelect = { viewModel.updateReasoningEffort(state.revision, it) },
        )
    }
    DesktopSection(
        title = "Service tier",
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        DesktopChoice(
            label = "",
            selected = settings.serviceTier,
            options = ServiceTier.entries.toList(),
            optionLabel = ServiceTier::desktopLabel,
            onSelect = { viewModel.updateServiceTier(state.revision, it) },
        )
    }
    DesktopSection(
        title = "Mode",
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        DesktopChoice(
            label = "",
            selected = settings.mode,
            options = ModeKind.entries.toList(),
            optionLabel = ModeKind::desktopLabel,
            onSelect = { viewModel.updateMode(state.revision, it) },
        )
    }
}

@Composable
private fun RenameSessionDesktopDialog(
    request: SessionSettingsEffect.RenameSession,
    onDismissRequest: () -> Unit,
    onRename: (String) -> Unit,
): Unit {
    var name by remember(request) { mutableStateOf(request.initialName) }
    val normalized = name.trim()
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Rename session") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.onPreviewKeyEvent { event ->
                    if (
                        event.type == KeyEventType.KeyDown &&
                        event.key == Key.Enter &&
                        !event.isShiftPressed &&
                        !event.isCtrlPressed &&
                        !event.isAltPressed &&
                        normalized.isNotEmpty()
                    ) {
                        onRename(normalized)
                        true
                    } else {
                        false
                    }
                },
                label = { Text("Session name") },
                prefix = { Text(">") },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(
                onClick = { onRename(normalized) },
                enabled = normalized.isNotEmpty(),
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun UsageResetDialogHost(
    state: UsageResetState,
    viewModel: GlobalSettingsViewModel,
): Unit {
    when (state) {
        UsageResetState.Hidden -> Unit
        is UsageResetState.Choosing -> AlertDialog(
            onDismissRequest = viewModel::dismissUsageReset,
            title = { Text("Usage limit resets") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "${state.request.availableCount} " +
                            if (state.request.availableCount == 1L) {
                                "usage limit reset available."
                            } else {
                                "usage limit resets available."
                            },
                    )
                    state.request.options.forEach { option ->
                        UsageResetOptionButton(
                            option = option,
                            onClick = { viewModel.selectUsageReset(option) },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = viewModel::dismissUsageReset) {
                    Text("Cancel")
                }
            },
        )

        is UsageResetState.Preparing -> UsageResetProgressDialog(
            message = "Preparing ${state.option.title}…",
        )

        is UsageResetState.Confirming -> AlertDialog(
            onDismissRequest = viewModel::returnToUsageResetChoices,
            title = { Text("Use this reset?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.option.title, fontWeight = FontWeight.SemiBold)
                    state.option.expiresAt?.let {
                        Text(
                            "Expires $it",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(state.option.description)
                }
            },
            confirmButton = {
                Button(onClick = viewModel::confirmUsageReset) {
                    Text("Use reset")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::returnToUsageResetChoices) {
                    Text("Go back")
                }
            },
        )

        is UsageResetState.Consuming -> UsageResetProgressDialog(
            message = "Resetting your usage…",
        )

        is UsageResetState.ConsumeFailed -> AlertDialog(
            onDismissRequest = viewModel::dismissUsageReset,
            title = { Text("Usage limit resets") },
            text = { Text("Couldn't reset usage. Please try again.") },
            confirmButton = {
                Button(onClick = viewModel::retryUsageReset) {
                    Text("Try again")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissUsageReset) {
                    Text("Close")
                }
            },
        )

        UsageResetState.PreparationFailed -> UsageResetMessageDialog(
            message = "Couldn't prepare a usage limit reset. Refresh usage and try again.",
            onDismissRequest = viewModel::dismissUsageReset,
        )

        is UsageResetState.Completed -> UsageResetMessageDialog(
            message = state.outcome.desktopMessage(state.selectedCredit),
            onDismissRequest = viewModel::dismissUsageReset,
        )
    }
}

@Composable
private fun UsageResetOptionButton(
    option: UsageResetOption,
    onClick: () -> Unit,
): Unit {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(option.title)
            option.expiresAt?.let { expiration ->
                Text(
                    "Expires $expiration",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun UsageResetProgressDialog(message: String): Unit {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Usage limit resets") },
        text = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text(message)
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun UsageResetMessageDialog(
    message: String,
    onDismissRequest: () -> Unit,
): Unit {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Usage limit resets") },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onDismissRequest) {
                Text("Close")
            }
        },
    )
}

private fun SettingsPage.desktopLabel(): String = when (this) {
    SettingsPage.Global -> "Global"
    SettingsPage.Session -> "Session"
    SettingsPage.NewSession -> "New session"
}

private fun KodexAuthSource.desktopLabel(): String = when (this) {
    KodexAuthSource.Codex -> "Codex"
    KodexAuthSource.Kodex -> "Kodex"
}

private fun NewLineKey.desktopLabel(): String = when (this) {
    NewLineKey.ShiftEnter -> "Shift+Enter"
    NewLineKey.Enter -> "Enter"
}

private fun SubmitKey.desktopLabel(): String = when (this) {
    SubmitKey.Enter -> "Enter"
    SubmitKey.CtrlEnter -> "Ctrl+Enter"
}

private fun ReasoningEffort.desktopLabel(): String = when (this) {
    ReasoningEffort.None -> "none"
    ReasoningEffort.Minimal -> "minimal"
    ReasoningEffort.Low -> "low"
    ReasoningEffort.Medium -> "medium"
    ReasoningEffort.High -> "high"
    ReasoningEffort.XHigh -> "xhigh"
    ReasoningEffort.Max -> "max"
    ReasoningEffort.Ultra -> "ultra"
    is ReasoningEffort.Custom -> wireName
}

private fun ServiceTier.desktopLabel(): String = when (this) {
    ServiceTier.Default -> "default"
    ServiceTier.Fast -> "fast"
    ServiceTier.Flex -> "flex"
}

private fun ModeKind.desktopLabel(): String = when (this) {
    ModeKind.Default -> "build"
    ModeKind.Plan -> "plan"
}

private fun OpenAiAuthState.Unavailable.desktopDescription(): String = when (this) {
    OpenAiAuthState.Unavailable.NotLoaded ->
        "Authentication credentials have not been loaded yet."

    OpenAiAuthState.Unavailable.CredentialsNotFound ->
        "No credentials were found in the selected authentication source."

    OpenAiAuthState.Unavailable.UnsupportedAuthMode ->
        "The selected credentials use an unsupported authentication mode."

    OpenAiAuthState.Unavailable.InvalidCredentials ->
        "The selected credentials are malformed or incomplete."

    OpenAiAuthState.Unavailable.CredentialSourceUnavailable ->
        "The selected credential source could not be read."

    OpenAiAuthState.Unavailable.UnexpectedFailure ->
        "Authentication failed because of an unexpected internal error."
}

private fun McpServerSettingsStatus.desktopLabel(): String = when (this) {
    McpServerSettingsStatus.Disabled -> "Disabled"
    McpServerSettingsStatus.Connecting -> "Connecting"
    is McpServerSettingsStatus.Healthy ->
        "Healthy ($toolCount ${if (toolCount == 1) "tool" else "tools"})"

    is McpServerSettingsStatus.Failed -> "Failed: ${reason.desktopLabel()}"
    McpServerSettingsStatus.Closed -> "Closed"
}

private fun McpClientFailureReason.desktopLabel(): String = when (this) {
    McpClientFailureReason.Transport -> "Transport"
    McpClientFailureReason.Initialization -> "Initialization"
    McpClientFailureReason.ConnectionLost -> "Connection lost"
    McpClientFailureReason.ToolCatalog -> "Tool catalog"
}

private fun CodexAccountUsageSnapshot.hasAvailableUsageReset(): Boolean {
    val detailedCredits = resetCredits.credits.orEmpty()
    val availableCount = resetCredits.availableCount ?: detailedCredits.size.toLong()
    return availableCount > 0L || detailedCredits.isNotEmpty()
}

private fun CodexAccountRateLimit.desktopDisplayLine(): String {
    val windows = listOfNotNull(primaryWindow, secondaryWindow)
        .joinToString(separator = " · ") { window -> window.desktopDisplayLabel() }
        .ifEmpty { "unavailable" }
    val reached = if (limitReached || !allowed) " · limit reached" else ""
    return "$name: $windows$reached"
}

private fun CodexAccountRateLimitWindow.desktopDisplayLabel(): String =
    "${durationSeconds.limitDurationLabel()} $usedPercent% used " +
        "(resets ${resetAfterSeconds.resetDelayLabel()})"

private fun Long.limitDurationLabel(): String {
    val seconds = coerceAtLeast(0L)
    return when {
        seconds > 0L && seconds % SecondsPerDay == 0L -> "${seconds / SecondsPerDay}d"
        seconds > 0L && seconds % SecondsPerHour == 0L -> "${seconds / SecondsPerHour}h"
        seconds > 0L && seconds % SecondsPerMinute == 0L -> "${seconds / SecondsPerMinute}m"
        else -> "${seconds}s"
    }
}

private fun Long.resetDelayLabel(): String {
    val seconds = coerceAtLeast(0L)
    return when {
        seconds == 0L -> "now"
        seconds >= SecondsPerDay -> "in ${seconds / SecondsPerDay}d"
        seconds >= SecondsPerHour -> "in ${seconds / SecondsPerHour}h"
        seconds >= SecondsPerMinute -> "in ${seconds / SecondsPerMinute}m"
        else -> "in ${seconds}s"
    }
}

private fun Long.groupedDecimal(): String {
    val raw = toString()
    val sign = raw.takeWhile { it == '-' }
    val digits = raw.removePrefix(sign)
    return sign + digits.reversed().chunked(3).joinToString(",").reversed()
}

private fun CodexRateLimitResetOutcome.desktopMessage(selectedCredit: Boolean): String =
    when (this) {
        CodexRateLimitResetOutcome.Reset -> "Usage reset."
        CodexRateLimitResetOutcome.AlreadyRedeemed ->
            "This reset was already used successfully."

        CodexRateLimitResetOutcome.NothingToReset ->
            "Your usage does not need a reset right now."

        CodexRateLimitResetOutcome.NoCredit -> if (selectedCredit) {
            "That reset is no longer available."
        } else {
            "No usage limit resets are available."
        }
    }

private val knownReasoningEfforts: List<ReasoningEffort> = listOf(
    ReasoningEffort.None,
    ReasoningEffort.Minimal,
    ReasoningEffort.Low,
    ReasoningEffort.Medium,
    ReasoningEffort.High,
    ReasoningEffort.XHigh,
    ReasoningEffort.Max,
    ReasoningEffort.Ultra,
)

private const val SecondsPerMinute: Long = 60L
private const val SecondsPerHour: Long = 60L * SecondsPerMinute
private const val SecondsPerDay: Long = 24L * SecondsPerHour
