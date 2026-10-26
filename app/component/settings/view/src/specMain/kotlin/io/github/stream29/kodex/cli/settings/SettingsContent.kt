package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameViewModel
import io.github.stream29.kodex.app.settings.contract.SettingsPage
import io.github.stream29.kodex.app.settings.contract.SettingsViewModel
import io.github.stream29.kodex.app.ui.SettingsRenderer
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryViewModel

public data class SettingsPageFields(
    public val preferences: SettingsRenderer,
    public val title: SettingsRenderer,
    public val defaults: SettingsRenderer,
    public val session: SettingsRenderer,
    public val authentication: SettingsRenderer,
)

public data class SettingsNavigationEntry(public val page: SettingsPage, public val label: String)
public enum class SettingsFieldGroup { Preferences, Title, Defaults, Session, Authentication }
public enum class SettingsModalFrame { ContextSource, Logout, UsageReset }

/** Fixed host capabilities, not mutable Settings or backend authorization. */
public data class SettingsHostCapabilities(
    public val directoryBrowsing: Boolean = true,
    public val contextPathEditing: Boolean = true,
    public val providerLogin: Boolean = true,
)

/** Renderer state and component frames only; no Settings child ownership or page authority. */
public interface SettingsRootRenderer {
    public val fields: SettingsPageFields
    public val hooks: HookDialogRenderer
    public val mcp: McpDialogRenderer
    public val frontendErrorHooksAvailable: Boolean
    public val frontendErrorHooksLabel: String

    @Composable
    public fun Frame(
        selectedPage: SettingsPage, navigation: List<SettingsNavigationEntry>,
        onSelect: (SettingsPage) -> Unit, onDismiss: () -> Unit,
        banner: @Composable () -> Unit, content: @Composable () -> Unit,
    )

    /** Clears renderer-only menu/focus state when the selected page changes. */
    @Composable
    public fun PreparePage(page: SettingsPage)

    @Composable public fun Menus(group: SettingsFieldGroup)
    @Composable public fun FailureBanner(onDismiss: () -> Unit)
    @Composable public fun Modal(
        kind: SettingsModalFrame, title: String, onDismiss: () -> Unit, content: @Composable () -> Unit,
    )
    @Composable public fun Rename(viewModel: SessionRenameViewModel, onDismiss: () -> Unit)
    @Composable public fun Directory(viewModel: WorkingDirectoryViewModel, onDismiss: () -> Unit)
}

public const val SettingsOperationFailureMessage: String =
    "Could not confirm a Settings operation. Check current values before retrying."

public expect fun SettingsPage.settingsLabel(): String

/**
 * Shared navigation and child composition. Borrows the exact owner; MCP URL effects
 * must be mounted separately under the retained Settings owner across Login.
 */
@Composable
public expect fun SettingsContent(
    viewModel: SettingsViewModel, renderer: SettingsRootRenderer,
    onDismissRequest: () -> Unit, onOpenLogin: () -> Unit,
    capabilities: SettingsHostCapabilities = SettingsHostCapabilities(),
)
