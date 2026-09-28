package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.rpc.models.NotificationHook
import io.github.stream29.kodex.rpc.models.NotificationHookType

/** Hook management entry point backed only by command-free manager state. */
@Composable
internal fun HookSettingsContent(
    hooks: List<NotificationHook>,
    onAdd: () -> Unit,
    onOpenDetails: (NotificationHook) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SettingsSectionHeaderBackground),
        ) {
            Text(
                "Hooks ",
                color = SettingsForeground,
                textStyle = TuiTheme.typography.title,
            )
            SettingsActionButton(label = "Add", onClick = onAdd)
        }
        if (hooks.isEmpty()) {
            Text(
                value = "None configured",
                color = SettingsForeground,
                textStyle = TextStyle.Dim,
            )
        } else {
            hooks.forEach { hook ->
                SettingsContentButton(
                    label = "${hook.name} ${hook.types.joinToString { it.settingsLabel() }}",
                    modifier = Modifier.fillMaxWidth(),
                    idleTextStyle = TuiTheme.typography.body + TextStyle.Bold,
                    onClick = { onOpenDetails(hook) },
                )
            }
        }
    }
}

internal fun NotificationHookType.settingsLabel(): String =
    when (this) {
        NotificationHookType.StopAssistantMessage -> "Assistant message"
        NotificationHookType.StopRequestUserInput -> "Request user input"
        NotificationHookType.StopSuggestSubagent -> "Suggest subagent"
        NotificationHookType.StopUnhandledError -> "Unhandled error"
    }
