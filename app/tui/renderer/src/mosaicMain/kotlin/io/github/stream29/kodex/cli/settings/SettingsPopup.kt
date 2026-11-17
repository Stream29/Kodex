package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.*
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.*
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.*
import io.github.stream29.kodex.app.hooksettings.HookEditorDraft
import io.github.stream29.kodex.app.hooksettings.HookSettingsDialog
import io.github.stream29.kodex.app.mcpsettings.McpEditorDraft
import io.github.stream29.kodex.app.mcpsettings.McpManualAuthorization
import io.github.stream29.kodex.app.mcpsettings.McpSettingsDialog
import io.github.stream29.kodex.mcp.contract.McpImportDecision
import io.github.stream29.kodex.mcp.contract.McpImportPreview
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameViewModel
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryViewModel
import io.github.stream29.kodex.cli.components.*
import io.github.stream29.kodex.cli.newsessiondefaults.DefaultsChoiceField
import io.github.stream29.kodex.cli.sessionrename.SessionRenamePopup
import io.github.stream29.kodex.cli.sessionrename.SessionRenamePresentation
import io.github.stream29.kodex.cli.sessionsettings.SessionChoiceField
import io.github.stream29.kodex.cli.workingdirectory.WorkingDirectoryPopup
import io.github.stream29.kodex.rpc.models.NotificationHook
import io.github.stream29.kodex.rpc.models.NotificationHookType

@Composable
internal fun BoxScope.SettingsPopup(
    viewModel: SettingsViewModel, onDismissRequest: () -> Unit, onOpenLogin: () -> Unit,
) {
    val session = rememberFourChoiceDropdowns(
        SessionChoiceField.Model.name,
        SessionChoiceField.Reasoning.name,
        SessionChoiceField.ServiceTier.name,
        SessionChoiceField.Questions.name,
    )
    val defaults = rememberFourChoiceDropdowns(
        DefaultsChoiceField.Model.name,
        DefaultsChoiceField.Reasoning.name,
        DefaultsChoiceField.ServiceTier.name,
        DefaultsChoiceField.Questions.name,
    )
    val preferences = rememberTwoChoiceDropdowns(
        PreferencesChoiceField.NewLineKey.name,
        PreferencesChoiceField.SubmitKey.name,
    )
    val title = rememberTwoChoiceDropdowns(
        TitleChoiceField.Model.name,
        TitleChoiceField.Reasoning.name,
    )
    val authentication = rememberTuiDropdownState()
    val renderer = remember(this, session, defaults, preferences, title, authentication) {
        MosaicSettingsRoot(this, session, defaults, preferences, title, authentication)
    }
    SettingsContent(viewModel, renderer, onDismissRequest, onOpenLogin)
}

internal class SettingsDropdownGroup(val dropdowns: List<TuiDropdownState>, val renderer: MosaicSettingsRenderer) {
    fun dismissAll() = dropdowns.forEach(TuiDropdownState::dismiss)
}

@Composable
internal fun rememberTwoChoiceDropdowns(firstKey: String, secondKey: String): SettingsDropdownGroup {
    val first = rememberTuiDropdownState()
    val second = rememberTuiDropdownState()
    return remember(firstKey, secondKey, first, second) {
        SettingsDropdownGroup(
            listOf(first, second),
            MosaicSettingsRenderer(mapOf(firstKey to first, secondKey to second)),
        )
    }
}

@Composable
internal fun rememberFourChoiceDropdowns(
    firstKey: String, secondKey: String, thirdKey: String, fourthKey: String,
): SettingsDropdownGroup {
    val first = rememberTuiDropdownState()
    val second = rememberTuiDropdownState()
    val third = rememberTuiDropdownState()
    val fourth = rememberTuiDropdownState()
    return remember(firstKey, secondKey, thirdKey, fourthKey, first, second, third, fourth) {
        SettingsDropdownGroup(
            listOf(first, second, third, fourth),
            MosaicSettingsRenderer(mapOf(firstKey to first, secondKey to second, thirdKey to third, fourthKey to fourth)),
        )
    }
}

private class MosaicSettingsRoot(
    private val box: BoxScope,
    private val session: SettingsDropdownGroup,
    private val defaults: SettingsDropdownGroup,
    private val preferences: SettingsDropdownGroup,
    private val title: SettingsDropdownGroup,
    private val authentication: TuiDropdownState,
) : SettingsRootRenderer {
    private val authFields = MosaicSettingsRenderer(
        mapOf("AuthenticationSource" to authentication), inlineTriggerKeys = setOf("AuthenticationSource"),
    )
    override val fields = SettingsPageFields(
        preferences.renderer, title.renderer, defaults.renderer, session.renderer, authFields,
    )
    override val hooks: HookDialogRenderer = MosaicHookDialogs(box)
    override val mcp: McpDialogRenderer = MosaicMcpDialogs(box)
    override val frontendErrorHooksLabel: String = "TUI local errors"

    @Composable
    override fun PreparePage(page: SettingsPage) {
        LaunchedEffect(page) {
            session.dismissAll()
            defaults.dismissAll()
            preferences.dismissAll()
            title.dismissAll()
            authentication.dismiss()
        }
    }

    @Composable
    override fun Frame(
        selectedPage: SettingsPage, navigation: List<SettingsNavigationEntry>,
        onSelect: (SettingsPage) -> Unit, onDismiss: () -> Unit,
        banner: @Composable () -> Unit, content: @Composable () -> Unit,
    ) = with(box) {
        val terminalSize = LocalTerminalState.current.size
        val width = (terminalSize.columns - 4).coerceIn(1, SettingsMaximumWidth)
        val height = (terminalSize.rows - 4).coerceAtLeast(1)
        val scroll = remember(selectedPage) { ScrollState() }
        val navigationWidth = SettingsNavigationWidth.coerceAtMost((width - 1).coerceAtLeast(1))
        val contentWidth = (width - navigationWidth).coerceAtLeast(1)
        TuiDialog(
            onDismissRequest = onDismiss,
            modifier = Modifier.width(width).height(height).background(SettingsDialogBackground),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Settings", Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                    color = SettingsForeground, textStyle = TuiTheme.typography.headline,
                )
                banner()
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    Column(Modifier.width(navigationWidth).fillMaxHeight().background(SettingsNavigationBackground)) {
                        navigation.forEach { entry ->
                            SettingsNavigationButton(
                                entry.label, modifier = Modifier.fillMaxWidth(), selected = entry.page == selectedPage,
                                onClick = { onSelect(entry.page) },
                            )
                        }
                    }
                    SettingsPageViewport(contentWidth, scroll, content)
                }
                TuiDialogActionRow(Modifier.fillMaxWidth().background(SettingsActionBackground)) {
                    SettingsActionButton("Close", onClick = onDismiss)
                }
            }
        }
    }

    @Composable
    override fun Menus(group: SettingsFieldGroup) = with(box) {
        val renderer = when (group) {
            SettingsFieldGroup.Preferences -> preferences.renderer
            SettingsFieldGroup.Title -> title.renderer
            SettingsFieldGroup.Defaults -> defaults.renderer
            SettingsFieldGroup.Session -> session.renderer
            SettingsFieldGroup.Authentication -> authFields
        }
        with(renderer) { Menus() }
    }

    @Composable
    override fun FailureBanner(onDismiss: () -> Unit) { SettingsOperationFailureBanner(true, onDismiss) }

    @Composable
    override fun Modal(kind: SettingsModalFrame, title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) = with(box) {
        when (kind) {
            SettingsModalFrame.ContextSource -> SettingsModalDialogFrame(title, 96, onDismiss, content)
            SettingsModalFrame.Logout -> AuthenticationLogoutFrame(title, onDismiss, content)
            SettingsModalFrame.UsageReset -> SettingsModalDialogFrame(title, 72, onDismiss, content)
        }
    }

    @Composable
    override fun Rename(viewModel: SessionRenameViewModel, onDismiss: () -> Unit) = with(box) {
        SessionRenamePopup(viewModel, onDismiss, onDismiss, SessionRenamePresentation.Labeled)
    }

    @Composable
    override fun Directory(viewModel: WorkingDirectoryViewModel, onDismiss: () -> Unit) = with(box) {
        WorkingDirectoryPopup(viewModel, onDismiss, {})
    }
}

internal class MosaicHookDialogs(private val box: BoxScope) : HookDialogRenderer {
    @Composable override fun Editor(
        editor: HookSettingsDialog.Editing, supportedTypes: Set<NotificationHookType>,
        onDraftChange: ((HookEditorDraft) -> HookEditorDraft) -> Unit, onDismiss: () -> Unit, onSave: () -> Unit,
    ) { with(box) { HookEditorDialog(editor, supportedTypes, onDraftChange, onDismiss, onSave) } }
    @Composable override fun Details(hook: NotificationHook, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
        with(box) { HookDetailsDialog(hook, onDismiss, onEdit, onDelete) }
    }
    @Composable override fun Deleting(hook: NotificationHook, error: String?, onDismiss: () -> Unit, onConfirm: () -> Unit) {
        with(box) { HookDeleteConfirmationDialog(hook, onDismiss, onConfirm, error) }
    }
}

@Composable
internal fun BoxScope.HookEditorDialog(
    editor: HookSettingsDialog.Editing,
    supportedTypes: Set<NotificationHookType> = NotificationHookType.entries.toSet(),
    onDraftChange: ((HookEditorDraft) -> HookEditorDraft) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val renderer = remember { MosaicSettingsRenderer() }
    HookEditorContent(editor, renderer, supportedTypes, onDraftChange, onDismiss, onSave) { title, dismiss, content ->
        SettingsModalDialogFrame(title, 96, dismiss, content)
    }
}

@Composable
internal fun BoxScope.HookDetailsDialog(hook: NotificationHook, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    HookDetailsContent(hook, onDismiss, onEdit, onDelete) { title, dismiss, content ->
        SettingsModalDialogFrame(title, 72, dismiss, content)
    }
}

@Composable
internal fun BoxScope.HookDeleteConfirmationDialog(
    hook: NotificationHook, onDismiss: () -> Unit, onConfirm: () -> Unit, error: String? = null,
) {
    HookDeleteContent(hook, error, onDismiss, onConfirm) { title, dismiss, content ->
        SettingsModalDialogFrame(title, 56, dismiss, content)
    }
}

internal class MosaicMcpDialogs(private val box: BoxScope) : McpDialogRenderer {
    @Composable override fun Editor(
        editor: McpSettingsDialog.Editing, onDraftChange: ((McpEditorDraft) -> McpEditorDraft) -> Unit,
        onDismiss: () -> Unit, onSave: () -> Unit,
    ) { with(box) { McpServerEditorDialog(editor, onDraftChange, onDismiss, onSave) } }
    @Composable override fun Details(
        server: McpServerSettingsState, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
        onSetEnabled: () -> Unit, onLogin: () -> Unit, onCancelLogin: () -> Unit, onLogout: () -> Unit, onReconnect: () -> Unit,
        manualAuthorization: McpManualAuthorization?,
    ) {
        with(box) {
            McpServerDetailsDialog(
                server, onDismiss, onEdit, onDelete, onSetEnabled,
                onLogin, onCancelLogin, onLogout, onReconnect, manualAuthorization,
            )
        }
    }
    @Composable override fun Deleting(server: McpServerSettingsState, error: String?, onDismiss: () -> Unit, onConfirm: () -> Unit) {
        with(box) { McpDeleteConfirmationDialog(server, onDismiss, onConfirm, error) }
    }
    @Composable override fun Import(
        preview: McpImportPreview?, decisions: Map<String, McpImportDecision>,
        onToggle: (String) -> Unit, onSelectAll: () -> Unit, onClear: () -> Unit, onFilter: (String) -> Unit,
        onApply: () -> Unit, onDismiss: () -> Unit, error: String?, failed: Boolean, onRetry: () -> Unit,
    ) {
        with(box) {
            McpImportDialog(preview, decisions, onToggle, onSelectAll, onClear, onFilter, onApply, onDismiss, error, failed, onRetry)
        }
    }
}

@Composable
internal fun BoxScope.McpServerEditorDialog(
    editor: McpSettingsDialog.Editing, onDraftChange: ((McpEditorDraft) -> McpEditorDraft) -> Unit,
    onDismiss: () -> Unit, onSave: () -> Unit,
) {
    val transport = rememberTuiDropdownState()
    val renderer = remember(transport) { MosaicSettingsRenderer(mapOf(McpChoiceField.Transport.name to transport)) }
    McpEditorContent(editor, renderer, onDraftChange, onDismiss, onSave) { title, dismiss, content ->
        SettingsModalDialogFrame(title, 84, dismiss, content)
    }
    with(renderer) { Menus() }
}

@Composable
internal fun BoxScope.McpServerDetailsDialog(
    server: McpServerSettingsState, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
    onSetEnabled: () -> Unit, onLogin: () -> Unit, onCancelLogin: () -> Unit, onLogout: () -> Unit, onReconnect: () -> Unit,
    manualAuthorization: McpManualAuthorization? = null,
) {
    McpDetailsContent(
        server, onDismiss, onEdit, onDelete, onSetEnabled, onLogin, onCancelLogin, onLogout, onReconnect,
        manualAuthorization = manualAuthorization,
    ) { title, dismiss, content ->
        SettingsModalDialogFrame(title, 84, dismiss, content)
    }
}

@Composable
internal fun BoxScope.McpDeleteConfirmationDialog(
    server: McpServerSettingsState, onDismiss: () -> Unit, onConfirm: () -> Unit, error: String? = null,
) {
    McpDeleteContent(server, error, onDismiss, onConfirm) { title, dismiss, content ->
        SettingsModalDialogFrame(title, 56, dismiss, content)
    }
}

@Composable
internal fun BoxScope.McpImportDialog(
    preview: McpImportPreview?, decisions: Map<String, McpImportDecision>,
    onToggle: (String) -> Unit, onSelectAll: () -> Unit, onClear: () -> Unit, onFilter: (String) -> Unit,
    onApply: () -> Unit, onDismiss: () -> Unit, error: String? = null, failed: Boolean = false, onRetry: () -> Unit = {},
) {
    val size = LocalTerminalState.current.size
    val width = (size.columns - 4).coerceIn(1, 76)
    val height = (size.rows - 4).coerceIn(1, 28)
    val scroll = remember(preview?.id) { ScrollState() }
    val renderer = remember { MosaicSettingsRenderer() }
    McpImportContent(
        preview, decisions, renderer, onToggle, onSelectAll, onClear, onFilter, onApply, onDismiss, error, failed, onRetry,
    ) { title, dismiss, body, footer ->
        TuiDialog(
            onDismissRequest = dismiss,
            modifier = Modifier.width(width).height(height).background(SettingsDialogBackground),
        ) {
            Column(Modifier.fillMaxWidth().background(SettingsDialogBackground)) {
                SettingsDialogHeader(title)
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll)) { body() }
                footer()
            }
        }
    }
}

@Composable
internal fun BoxScope.SettingsModalDialogFrame(
    title: String, maximumWidth: Int, onDismiss: () -> Unit, content: @Composable () -> Unit,
) {
    val width = (LocalTerminalState.current.size.columns - 4).coerceIn(1, maximumWidth)
    TuiDialog(onDismissRequest = onDismiss, modifier = Modifier.width(width).background(SettingsDialogBackground)) {
        Column(Modifier.fillMaxWidth().background(SettingsDialogBackground)) {
            SettingsDialogHeader(title)
            content()
        }
    }
}

@Composable
internal fun BoxScope.AuthenticationLogoutFrame(
    title: String, onDismiss: () -> Unit, content: @Composable () -> Unit,
) {
    val width = (LocalTerminalState.current.size.columns - 4).coerceIn(1, 72)
    TuiDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).background(TuiTheme.colorScheme.surfaceContainer),
    ) {
        Column {
            Text(title, textStyle = TuiTheme.typography.headline)
            content()
        }
    }
}

@Composable
private fun SettingsDialogHeader(title: String) {
    Text(
        title, Modifier.fillMaxWidth().background(SettingsHeaderBackground),
        color = SettingsForeground, textStyle = TuiTheme.typography.headline,
    )
}

@Composable
internal fun SettingsOperationFailureBanner(failed: Boolean, onDismiss: () -> Unit) {
    if (!failed) return
    Column(Modifier.fillMaxWidth().background(SettingsActionBackground)) {
        Text(SettingsOperationFailureMessage, color = SettingsErrorForeground)
        SettingsActionButton("Dismiss", onClick = onDismiss)
    }
}

@Composable
internal fun SettingsPageViewport(width: Int, scrollState: ScrollState, content: @Composable () -> Unit) {
    Column(
        Modifier.width(width).fillMaxHeight().background(SettingsHomeBackground).verticalScroll(scrollState),
    ) { content() }
}

private const val SettingsMaximumWidth: Int = 84
private const val SettingsNavigationWidth: Int = 18
