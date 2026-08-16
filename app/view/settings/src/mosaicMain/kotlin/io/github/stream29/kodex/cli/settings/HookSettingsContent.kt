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
import io.github.stream29.kodex.hook.contract.HookManagedState
import io.github.stream29.kodex.hook.contract.HookType

/** Hook management entry point backed only by command-free manager state. */
@Composable
internal fun HookSettingsContent(
    hooks: List<HookManagedState>,
    onAdd: () -> Unit,
    onOpenDetails: (HookManagedState) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SettingsSectionHeaderBackground),
        ) {
            Text("Hooks ", color = SettingsForeground)
            TuiButton(label = "Add", color = SettingsForeground, onClick = onAdd)
        }
        if (hooks.isEmpty()) {
            Text(
                value = "None configured",
                color = SettingsForeground,
                textStyle = TextStyle.Dim,
            )
        } else {
            hooks.forEach { hook ->
                TuiButton(
                    label = "${hook.name} · ${hook.type.settingsLabel()}",
                    modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground),
                    color = SettingsForeground,
                    onClick = { onOpenDetails(hook) },
                )
            }
        }
    }
}

internal fun HookType.settingsLabel(): String =
    when (this) {
        HookType.PreToolUse -> "Pre tool use"
        HookType.PostToolUse -> "Post tool use"
        HookType.UserPromptSubmit -> "User prompt submit"
        HookType.Stop -> "Stop"
        HookType.PreCompact -> "Pre compact"
        HookType.PostCompact -> "Post compact"
    }
