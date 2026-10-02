package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxHeight
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.cli.accountusage.AccountUsageComponent
import io.github.stream29.kodex.cli.authenticationsettings.AuthenticationSettingsOverlays
import io.github.stream29.kodex.cli.authenticationsettings.AuthenticationSettingsPanel
import io.github.stream29.kodex.cli.components.*
import io.github.stream29.kodex.cli.sessionrename.SessionRenamePopup
import io.github.stream29.kodex.cli.sessionrename.SessionRenamePresentation
import io.github.stream29.kodex.cli.usagereset.UsageResetDialogHost
import io.github.stream29.kodex.cli.workingdirectory.WorkingDirectoryPopup
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.utils.externalurl.OpenExternalUrlResult
import io.github.stream29.kodex.utils.externalurl.openExternalUrl

/**
 * Navigation, scroll layout and host overlays for stable Settings children.
 * Component state/drafts/commands remain in their exact children. The host renders
 * the shared failure once and never closes a component merely because its page is hidden.
 */
@Composable
public fun BoxScope.SettingsPopup(
    viewModel: SettingsViewModel,
    onDismissRequest: () -> Unit,
    onOpenLogin: () -> Unit,
) {
    val selectedPage by viewModel.selectedPage.collectAsState()
    val operationFailed by viewModel.global.operationFailure.collectAsState()
    val dropdowns = SettingsDropdownStates(
        rememberTuiDropdownState(), rememberTuiDropdownState(),
        rememberTuiDropdownState(), rememberTuiDropdownState(),
    )
    val preferencesDropdowns = rememberApplicationPreferencesDropdowns()
    val titleDropdowns = rememberSessionTitleSettingsDropdowns()
    val authenticationDropdown = rememberTuiDropdownState()
    var renameRequest by remember(viewModel) { mutableStateOf<SessionSettingsEffect.RenameSession?>(null) }
    val currentOpenLogin by rememberUpdatedState(onOpenLogin)
    LaunchedEffect(viewModel.global) {
        viewModel.global.effects.collect { effect ->
            when (effect) { GlobalSettingsEffect.OpenLogin -> currentOpenLogin() }
        }
    }
    // One handler across page navigation, not a renderer-local authentication lifetime.
    McpSettingsEffects(viewModel.global.mcpSettings) { url ->
        openExternalUrl(url) !is OpenExternalUrlResult.Failed
    }
    LaunchedEffect(viewModel.session) {
        viewModel.session.effects.collect { effect ->
            when (effect) { is SessionSettingsEffect.RenameSession -> renameRequest = effect }
        }
    }
    LaunchedEffect(selectedPage) {
        renameRequest = null
        dropdowns.dismissAll()
        preferencesDropdowns.newLineKey.dismiss()
        preferencesDropdowns.submitKey.dismiss()
        titleDropdowns.model.dismiss()
        titleDropdowns.reasoning.dismiss()
        authenticationDropdown.dismiss()
    }

    val terminalSize = LocalTerminalState.current.size
    val width = (terminalSize.columns - 4).coerceIn(1, SettingsMaximumWidth)
    val height = (terminalSize.rows - 4).coerceAtLeast(1)
    val pageScrollState = remember(selectedPage) { ScrollState() }
    val navigationWidth = SettingsNavigationWidth.coerceAtMost((width - 1).coerceAtLeast(1))
    val contentWidth = (width - navigationWidth).coerceAtLeast(1)
    TuiDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.width(width).height(height).background(SettingsDialogBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                value = "Settings",
                modifier = Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                color = SettingsForeground,
                textStyle = TuiTheme.typography.headline,
            )
            SettingsOperationFailureBanner(operationFailed, viewModel.global::dismissOperationFailure)
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    modifier = Modifier.width(navigationWidth).fillMaxHeight()
                        .background(SettingsNavigationBackground),
                ) {
                    SettingsPage.entries.forEach { candidate ->
                        SettingsNavigationButton(
                            label = candidate.settingsLabel(), modifier = Modifier.fillMaxWidth(),
                            selected = candidate == selectedPage,
                            onClick = { viewModel.selectPage(candidate) },
                        )
                    }
                }
                SettingsPageViewport(contentWidth, pageScrollState) {
                    when (selectedPage) {
                        SettingsPage.General -> ApplicationPreferencesPanel(
                            viewModel.global.applicationPreferences, preferencesDropdowns,
                            showOperationFailure = false,
                        )
                        SettingsPage.ContextSources -> ContextSourceSettingsPanel(
                            viewModel.global.contextSourceSettings, showOperationFailure = false,
                        )
                        SettingsPage.OpenAi -> SettingsSection(title = "Account") {
                            AuthenticationSettingsPanel(
                                viewModel.global.authenticationSettings, authenticationDropdown,
                                showOperationFailure = false,
                            )
                            AccountUsageComponent(viewModel.global.accountUsage, showOperationFailure = false)
                        }
                        SettingsPage.Mcp -> McpSettingsPanel(
                            viewModel.global.mcpSettings, showOperationFailure = false,
                        )
                        SettingsPage.Hooks -> HookSettingsPanel(
                            viewModel.global.hookSettings, showOperationFailure = false,
                        )
                        SettingsPage.CurrentSession -> SessionSettingsContent(viewModel.session, dropdowns)
                        SettingsPage.NewSession -> {
                            val state by viewModel.newSession.state.collectAsState()
                            SettingsSection(title = "Model behavior") {
                                NewSessionConfigurationContent(state, dropdowns)
                            }
                            SessionTitleSettingsPanel(
                                viewModel.global.sessionTitleSettings, titleDropdowns,
                                showOperationFailure = false,
                            )
                        }
                    }
                }
            }
            TuiDialogActionRow(
                modifier = Modifier.fillMaxWidth().background(SettingsActionBackground),
            ) { SettingsActionButton(label = "Close", onClick = onDismissRequest) }
        }
    }

    when (selectedPage) {
        SettingsPage.General -> ApplicationPreferencesDropdownMenus(
            viewModel.global.applicationPreferences, preferencesDropdowns,
        )
        SettingsPage.ContextSources -> ContextSourceSettingsDialogs(viewModel.global.contextSourceSettings)
        SettingsPage.OpenAi -> {
            AuthenticationSettingsOverlays(viewModel.global.authenticationSettings, authenticationDropdown)
            UsageResetDialogHost(viewModel.global.usageReset)
        }
        SettingsPage.NewSession -> {
            NewSessionSettingsDropdownMenus(viewModel.newSession, dropdowns)
            SessionTitleSettingsDropdownMenus(viewModel.global.sessionTitleSettings, titleDropdowns)
        }
        SettingsPage.CurrentSession -> SessionSettingsDropdownMenus(viewModel.session, dropdowns)
        SettingsPage.Mcp, SettingsPage.Hooks -> Unit
    }
    McpSettingsDialogs(viewModel.global.mcpSettings)
    HookSettingsDialogs(viewModel.global.hookSettings)
    renameRequest?.let { request ->
        val renameChild = remember(viewModel.session, request) {
            createSessionSettingsRenameChild(viewModel.session, request)
        }
        SessionRenamePopup(
            viewModel = renameChild, presentation = SessionRenamePresentation.Labeled,
            onDismissRequest = { if (renameRequest === request) renameRequest = null },
            onSubmitted = { if (renameRequest === request) renameRequest = null },
        )
    }
    val directoryPicker by viewModel.session.directoryPicker.collectAsState()
    directoryPicker?.let { picker ->
        WorkingDirectoryPopup(
            viewModel = picker.selection,
            onDismissRequest = { viewModel.session.dismissWorkingDirectoryPicker(picker) },
            onSelected = {},
        )
    }
}

@Composable
internal fun SettingsOperationFailureBanner(failed: Boolean, onDismiss: () -> Unit) {
    if (!failed) return
    Column(modifier = Modifier.fillMaxWidth().background(SettingsActionBackground)) {
        Text(
            value = "Could not confirm a Settings operation. Check current values before retrying.",
            color = SettingsErrorForeground,
        )
        SettingsActionButton(label = "Dismiss", onClick = onDismiss)
    }
}

@Composable
internal fun SettingsPageViewport(width: Int, scrollState: ScrollState, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.width(width).fillMaxHeight().background(SettingsHomeBackground)
            .verticalScroll(scrollState),
    ) { content() }
}

@Composable
private fun SessionSettingsContent(viewModel: SessionSettingsViewModel, dropdowns: SettingsDropdownStates) {
    val state by viewModel.state.collectAsState()
    when (val current = state) {
        SessionSettingsState.Unavailable -> Text(
            value = "No selected session",
            modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground),
            color = SettingsForeground,
        )
        is SessionSettingsState.Available -> {
            val snapshot = current.snapshot
            SettingsSection(title = "Identity") {
                SettingsItem(label = "Session name", supportingText = snapshot.sessionName) {
                    SettingsActionButton(label = "Rename", onClick = { viewModel.requestRename(snapshot.revision) })
                }
                SettingsPathField(
                    label = "Working directory", value = snapshot.configuration.workingDirectory.toString(),
                    enabled = snapshot.editable,
                    onBrowse = { viewModel.requestWorkingDirectory(snapshot.revision) },
                )
            }
            SettingsSection(title = "Model behavior") {
                ConfigurationSettingsContent(snapshot, dropdowns)
            }
        }
    }
}

@Composable
internal fun SettingsPathField(
    label: String, value: String, enabled: Boolean = true, onBrowse: () -> Unit,
) {
    SettingsItem(label = label, supportingText = value, enabled = enabled) {
        SettingsActionButton(label = "Browse", enabled = enabled, onClick = onBrowse)
    }
}

@Composable
private fun ConfigurationSettingsContent(snapshot: SessionSettingsSnapshot, dropdowns: SettingsDropdownStates) {
    val configuration = snapshot.configuration
    SettingsDropdownField("Model", configuration.model.value, dropdowns.model, enabled = snapshot.editable)
    SettingsDropdownField("Reasoning", configuration.reasoningEffort.displayName(), dropdowns.reasoning, enabled = snapshot.editable)
    SettingsDropdownField("Service tier", configuration.serviceTier.displayName(), dropdowns.serviceTier, enabled = snapshot.editable)
    SettingsDropdownField(
        "Questions", configuration.requestUserInputMode.displayName(), dropdowns.requestUserInputMode,
        enabled = snapshot.editable, supportingText = "Controls whether the agent may pause to ask for input.",
    )
}

@Composable
private fun NewSessionConfigurationContent(state: NewSessionSettingsState, dropdowns: SettingsDropdownStates) {
    SettingsDropdownField("Model", state.settings.model.value, dropdowns.model)
    SettingsDropdownField("Reasoning", state.settings.reasoningEffort.displayName(), dropdowns.reasoning)
    SettingsDropdownField("Service tier", state.settings.serviceTier.displayName(), dropdowns.serviceTier)
    SettingsDropdownField(
        "Questions", state.settings.requestUserInputMode.displayName(), dropdowns.requestUserInputMode,
        supportingText = "Controls whether the agent may pause to ask for input.",
    )
}

@Composable
private fun BoxScope.SessionSettingsDropdownMenus(viewModel: SessionSettingsViewModel, dropdowns: SettingsDropdownStates) {
    val state by viewModel.state.collectAsState()
    val available = state as? SessionSettingsState.Available ?: return
    val snapshot = available.snapshot
    val configuration = snapshot.configuration
    TuiDropdownMenu(
        dropdownState = dropdowns.model, options = available.modelOptions, selected = configuration.model,
        optionLabel = OpenAiModelId::value, enabled = snapshot.editable, backgroundColor = PopupMenuBackground,
        onSelect = { viewModel.updateModel(snapshot.revision, it) },
    )
    TuiDropdownMenu(
        dropdownState = dropdowns.reasoning, options = knownReasoningEfforts, selected = configuration.reasoningEffort,
        optionLabel = ReasoningEffort::displayName, enabled = snapshot.editable, backgroundColor = PopupMenuBackground,
        onSelect = { viewModel.updateReasoningEffort(snapshot.revision, it) },
    )
    TuiDropdownMenu(
        dropdownState = dropdowns.serviceTier, options = ServiceTier.entries.toList(), selected = configuration.serviceTier,
        optionLabel = ServiceTier::displayName, enabled = snapshot.editable, backgroundColor = PopupMenuBackground,
        onSelect = { viewModel.updateServiceTier(snapshot.revision, it) },
    )
    TuiDropdownMenu(
        dropdownState = dropdowns.requestUserInputMode, options = RequestUserInputMode.entries.toList(),
        selected = configuration.requestUserInputMode, optionLabel = RequestUserInputMode::displayName,
        enabled = snapshot.editable, backgroundColor = PopupMenuBackground,
        onSelect = { viewModel.updateRequestUserInputMode(snapshot.revision, it) },
    )
}

@Composable
private fun BoxScope.NewSessionSettingsDropdownMenus(viewModel: NewSessionSettingsViewModel, dropdowns: SettingsDropdownStates) {
    val state by viewModel.state.collectAsState()
    TuiDropdownMenu(
        dropdownState = dropdowns.model, options = state.modelOptions, selected = state.settings.model,
        optionLabel = OpenAiModelId::value, backgroundColor = PopupMenuBackground,
        onSelect = { viewModel.updateModel(state.revision, it) },
    )
    TuiDropdownMenu(
        dropdownState = dropdowns.reasoning, options = knownReasoningEfforts, selected = state.settings.reasoningEffort,
        optionLabel = ReasoningEffort::displayName, backgroundColor = PopupMenuBackground,
        onSelect = { viewModel.updateReasoningEffort(state.revision, it) },
    )
    TuiDropdownMenu(
        dropdownState = dropdowns.serviceTier, options = ServiceTier.entries.toList(), selected = state.settings.serviceTier,
        optionLabel = ServiceTier::displayName, backgroundColor = PopupMenuBackground,
        onSelect = { viewModel.updateServiceTier(state.revision, it) },
    )
    TuiDropdownMenu(
        dropdownState = dropdowns.requestUserInputMode, options = RequestUserInputMode.entries.toList(),
        selected = state.settings.requestUserInputMode, optionLabel = RequestUserInputMode::displayName,
        backgroundColor = PopupMenuBackground,
        onSelect = { viewModel.updateRequestUserInputMode(state.revision, it) },
    )
}

private class SettingsDropdownStates(
    val model: TuiDropdownState, val reasoning: TuiDropdownState,
    val serviceTier: TuiDropdownState, val requestUserInputMode: TuiDropdownState,
) {
    fun dismissAll() {
        model.dismiss(); reasoning.dismiss(); serviceTier.dismiss(); requestUserInputMode.dismiss()
    }
}

internal fun SettingsPage.settingsLabel(): String = when (this) {
    SettingsPage.General -> "General"
    SettingsPage.ContextSources -> "Context sources"
    SettingsPage.OpenAi -> "OpenAI"
    SettingsPage.Mcp -> "MCP"
    SettingsPage.Hooks -> "Hooks"
    SettingsPage.CurrentSession -> "Current session"
    SettingsPage.NewSession -> "New session"
}

private fun ReasoningEffort.displayName(): String = when (this) {
    ReasoningEffort.None -> "none"
    ReasoningEffort.Minimal -> "minimal"
    ReasoningEffort.Low -> "low"
    ReasoningEffort.Medium -> "medium"
    ReasoningEffort.High -> "high"
    ReasoningEffort.XHigh -> "xhigh"
    ReasoningEffort.Max -> "max"
    is ReasoningEffort.Custom -> wireName
}

private fun ServiceTier.displayName(): String = when (this) {
    ServiceTier.Default -> "default"
    ServiceTier.Fast -> "fast"
    ServiceTier.Flex -> "flex"
}

private fun RequestUserInputMode.displayName(): String = when (this) {
    RequestUserInputMode.AskUser -> "ask user"
    RequestUserInputMode.NoQuestion -> "no question"
}

private val knownReasoningEfforts = listOf(
    ReasoningEffort.None, ReasoningEffort.Minimal, ReasoningEffort.Low, ReasoningEffort.Medium,
    ReasoningEffort.High, ReasoningEffort.XHigh, ReasoningEffort.Max,
)
private const val SettingsMaximumWidth: Int = 84
private const val SettingsNavigationWidth: Int = 18
