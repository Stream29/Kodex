package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.paneTitle
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
    override val frontendErrorHooksLabel: String = "Frontend errors"

    @Composable
    override fun Frame(
        selectedPage: SettingsPage, navigation: List<SettingsNavigationEntry>,
        onSelect: (SettingsPage) -> Unit, onDismiss: () -> Unit,
        banner: @Composable () -> Unit, content: @Composable () -> Unit,
    ) {
        val pageScroll = remember(selectedPage) { ScrollState(0) }
        var pageHeight by remember(selectedPage) { mutableIntStateOf(1) }
        var navigationFocused by remember { mutableStateOf(false) }
        fun pageKey(event: KeyEvent): Boolean {
            if (event.type != KeyEventType.KeyDown || event.isAltPressed || event.isCtrlPressed || event.isMetaPressed || event.isShiftPressed) return false
            val direction = when (event.key) {
                Key.PageUp -> -1
                Key.PageDown -> 1
                else -> return false
            }
            return pageScroll.dispatchRawDelta((direction * pageHeight).toFloat()) != 0f
        }
        ComposeDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                Modifier.padding(12.dp).widthIn(max = 1040.dp).fillMaxWidth().heightIn(max = 780.dp),
                shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(
                    Modifier.fillMaxWidth().onPreviewKeyEvent { navigationFocused && pageKey(it) }.onKeyEvent(::pageKey),
                ) {
                    Box(Modifier.padding(12.dp)) {
                        Text("Settings", style = MaterialTheme.typography.titleLarge)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                        val wide = maxWidth >= 720.dp
                        @Composable fun Navigation() {
                            navigation.forEach { entry ->
                                key(entry.page) {
                                    val reveal = remember { BringIntoViewRequester() }
                                    val isSelected = entry.page == selectedPage
                                    val modifier = (if (wide) Modifier.fillMaxWidth() else Modifier)
                                        .bringIntoViewRequester(reveal).semantics {
                                            contentDescription = "Settings page ${entry.page.name}"
                                            role = Role.Tab
                                            selected = isSelected
                                        }
                                    CompactTextButton(
                                        { onSelect(entry.page) }, modifier,
                                        colors = ButtonDefaults.textButtonColors(
                                            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                            contentColor = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        ),
                                    ) {
                                        Text(entry.label, if (wide) Modifier.weight(1f) else Modifier,
                                            style = MaterialTheme.typography.bodyMedium)
                                    }
                                    LaunchedEffect(isSelected) { if (isSelected) reveal.bringIntoView() }
                                }
                            }
                        }
                        @Composable fun Page() {
                            key(selectedPage) {
                                Column(
                                    Modifier.fillMaxWidth().semantics { contentDescription = "Settings content ${selectedPage.name}" }
                                        .onSizeChanged { pageHeight = it.height.coerceAtLeast(1) }
                                        .verticalScroll(pageScroll).padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) { banner(); content() }
                            }
                        }
                        if (wide) {
                            Row(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.width(192.dp).verticalScroll(rememberScrollState()).padding(8.dp)
                                        .onFocusChanged { navigationFocused = it.hasFocus }.focusGroup(),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) { Navigation() }
                                Box(Modifier.weight(1f)) { Page() }
                            }
                        } else {
                            Column {
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)
                                        .onFocusChanged { navigationFocused = it.hasFocus }.focusGroup(),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                ) { Navigation() }
                                Box(Modifier.weight(1f, fill = false)) { Page() }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
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
            Surface(
                modifier = Modifier.semantics { paneTitle = title },
                shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    Modifier.widthIn(max = 760.dp).heightIn(max = 720.dp)
                        .semantics { contentDescription = "Settings modal content" }.verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
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
                Surface(
                    modifier = Modifier.semantics { paneTitle = "Rename session" },
                    shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(
                        Modifier.widthIn(max = 600.dp).heightIn(max = 640.dp)
                            .semantics { contentDescription = "Rename dialog content" }.verticalScroll(rememberScrollState()).padding(12.dp),
                    ) { content() }
                }
            }
        })
    }

    @Composable
    override fun Directory(viewModel: WorkingDirectoryViewModel, onDismiss: () -> Unit) {
        WorkingDirectoryContent(viewModel, directory, onDismiss, onSelected = {})
    }
}
