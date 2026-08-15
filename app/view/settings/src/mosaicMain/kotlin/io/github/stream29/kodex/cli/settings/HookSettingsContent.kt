package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.hook.contract.HookCodexSourceKind
import io.github.stream29.kodex.hook.contract.HookEvent
import io.github.stream29.kodex.hook.contract.HookManagedSourceState

/** Hook management entry point backed only by sanitized manager state. */
@Composable
internal fun HookSettingsContent(
    featureEnabled: Boolean,
    sources: List<HookManagedSourceState>,
    onSetFeatureEnabled: (Boolean) -> Unit,
    onAdd: () -> Unit,
    onEdit: (HookManagedSourceState) -> Unit,
    onDelete: (HookManagedSourceState) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onImport: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground)) {
        Text("Hooks", color = SettingsForeground)
        Row {
            TuiButton(
                label = if (featureEnabled) "Disable all" else "Enable all",
                color = SettingsForeground,
                onClick = { onSetFeatureEnabled(!featureEnabled) },
            )
            Text(" ")
            TuiButton(label = "Add", color = SettingsForeground, onClick = onAdd)
            Text(" ")
            TuiButton(
                label = "Import from Codex",
                color = SettingsForeground,
                onClick = onImport,
            )
        }
        if (sources.isEmpty()) {
            Text(
                value = "None configured",
                color = SettingsForeground,
                textStyle = TextStyle.Dim,
            )
        } else {
            sources.forEach { source ->
                HookSettingsRow(
                    source = source,
                    onEdit = { onEdit(source) },
                    onDelete = { onDelete(source) },
                    onSetEnabled = {
                        onSetEnabled(source.sourceId, !source.enabled)
                    },
                )
            }
        }
    }
}

@Composable
private fun HookSettingsRow(
    source: HookManagedSourceState,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetEnabled: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground)) {
        Text(
            value = buildString {
                append(source.name)
                append(" · ")
                append(if (source.enabled) "Enabled" else "Disabled")
                append(" · ")
                append(source.commandCount)
                append(if (source.commandCount == 1) " command" else " commands")
            },
            color = SettingsForeground,
            textStyle = TextStyle.Dim,
        )
        if (source.configuredEvents.isNotEmpty()) {
            Text(
                value = "Events: ${source.configuredEvents.joinToString { it.settingsLabel() }}",
                color = SettingsForeground,
                textStyle = TextStyle.Dim,
            )
        }
        if (source.environmentNames.isNotEmpty()) {
            Text(
                value = "Environment: ${source.environmentNames.joinToString()} (values hidden)",
                color = SettingsForeground,
                textStyle = TextStyle.Dim,
            )
        }
        source.importedFrom?.let { imported ->
            Text(
                value = "Imported from ${imported.sourceKind.settingsLabel()}: " +
                    imported.normalizedPath,
                color = SettingsForeground,
                textStyle = TextStyle.Dim,
            )
        }
        Row {
            TuiButton(
                label = if (source.enabled) "Disable" else "Enable",
                color = SettingsForeground,
                onClick = onSetEnabled,
            )
            Text(" ")
            TuiButton(label = "Edit", color = SettingsForeground, onClick = onEdit)
            Text(" ")
            TuiButton(label = "Delete", color = SettingsForeground, onClick = onDelete)
        }
    }
}

internal fun HookEvent.settingsLabel(): String =
    when (this) {
        HookEvent.PreToolUse -> "Pre tool"
        HookEvent.PermissionRequest -> "Permission"
        HookEvent.PostToolUse -> "Post tool"
        HookEvent.PreCompact -> "Pre compact"
        HookEvent.PostCompact -> "Post compact"
        HookEvent.UserPromptSubmit -> "Prompt"
        HookEvent.Stop -> "Stop"
    }

internal fun HookCodexSourceKind.settingsLabel(): String =
    when (this) {
        HookCodexSourceKind.User -> "user"
        HookCodexSourceKind.Project -> "project"
    }
