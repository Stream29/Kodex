package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.stream29.kodex.app.ui.compose.EditorDialog as ComposeDialog
import io.github.stream29.kodex.app.sessionrename.SessionRenameContent
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameViewModel
import io.github.stream29.kodex.app.settings.contract.SettingsPage
import io.github.stream29.kodex.app.ui.*
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryViewModel
import io.github.stream29.kodex.cli.pathpicker.DirectoryPickerRenderer
import io.github.stream29.kodex.cli.sessionrename.SessionRenamePresentation
import io.github.stream29.kodex.cli.settings.*
import io.github.stream29.kodex.cli.workingdirectory.WorkingDirectoryContent

@Composable
public actual fun rememberComposeSettingsRootRenderer(): SettingsRootRenderer {
    val fields = SettingsPageFields(
        rememberComposeSettingsRenderer(), rememberComposeSettingsRenderer(), rememberComposeSettingsRenderer(),
        rememberComposeSettingsRenderer(), rememberComposeSettingsRenderer(),
    )
    val hooks = rememberComposeHookDialogRenderer()
    val mcp = rememberComposeMcpDialogRenderer()
    val directory = rememberComposeDirectoryPickerRenderer()
    return remember(fields, hooks, mcp, directory) { ComposeSettingsRoot(fields, hooks, mcp, directory) }
}

private class ComposeSettingsRoot(
    override val fields: SettingsPageFields,
    override val hooks: HookDialogRenderer,
    override val mcp: McpDialogRenderer,
    private val directory: DirectoryPickerRenderer,
) : SettingsRootRenderer {
    override val frontendErrorHooksAvailable: Boolean = false
    override val frontendErrorHooksLabel: String = "Frontend errors"

    @Composable
    override fun Frame(
        selectedPage: SettingsPage, navigation: List<SettingsNavigationEntry>,
        onSelect: (SettingsPage) -> Unit, onDismiss: () -> Unit,
        banner: @Composable () -> Unit, content: @Composable () -> Unit,
    ) {
        ComposeDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                Modifier.padding(24.dp).widthIn(max = 1040.dp).fillMaxWidth().heightIn(max = 780.dp),
                shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Settings", style = MaterialTheme.typography.headlineSmall)
                        banner()
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                        val wide = maxWidth >= 720.dp
                        @Composable fun Navigation() {
                            navigation.forEach { entry ->
                                val modifier = Modifier.semantics { contentDescription = "Settings page ${entry.page.name}" }
                                if (wide) {
                                    NavigationDrawerItem(
                                        label = { Text(entry.label) }, selected = entry.page == selectedPage,
                                        onClick = { onSelect(entry.page) }, modifier = modifier,
                                    )
                                } else {
                                    FilterChip(
                                        selected = entry.page == selectedPage, onClick = { onSelect(entry.page) },
                                        label = { Text(entry.label) }, modifier = modifier,
                                    )
                                }
                            }
                        }
                        @Composable fun Page() {
                            key(selectedPage) {
                                Column(
                                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                                    verticalArrangement = Arrangement.spacedBy(24.dp),
                                ) { content() }
                            }
                        }
                        if (wide) {
                            Row(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.width(208.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) { Navigation() }
                                Box(Modifier.weight(1f)) { Page() }
                            }
                        } else {
                            Column {
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) { Navigation() }
                                Box(Modifier.weight(1f, fill = false)) { Page() }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                        UiActions { UiAction("Close", onClick = onDismiss) }
                    }
                }
            }
        }
    }

    @Composable override fun PreparePage(page: SettingsPage) = Unit
    @Composable override fun Menus(group: SettingsFieldGroup) = Unit
    @Composable override fun FailureBanner(onDismiss: () -> Unit) {
        Column {
            UiText(SettingsOperationFailureMessage, UiTextRole.Error)
            UiAction("Dismiss", onClick = onDismiss)
        }
    }

    @Composable
    override fun Modal(
        kind: SettingsModalFrame, title: String, onDismiss: () -> Unit, content: @Composable () -> Unit,
    ) {
        ComposeDialog(onDismissRequest = onDismiss) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(
                    Modifier.widthIn(max = 760.dp).heightIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    UiText(title, UiTextRole.Title)
                    content()
                }
            }
        }
    }

    @Composable
    override fun Rename(viewModel: SessionRenameViewModel, onDismiss: () -> Unit) {
        SessionRenameContent(viewModel, onDismiss, SessionRenamePresentation.Labeled, frame = { content ->
            ComposeDialog(onDismissRequest = onDismiss) {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.widthIn(max = 600.dp).padding(24.dp)) { content() }
                }
            }
        })
    }

    @Composable
    override fun Directory(viewModel: WorkingDirectoryViewModel, onDismiss: () -> Unit) {
        WorkingDirectoryContent(viewModel, directory, onDismiss, onSelected = {})
    }
}
