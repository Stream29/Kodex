package io.github.stream29.kodex.cli.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import io.github.stream29.kodex.app.ui.*

internal object BrowserHookSettingsLayoutRenderer : HookSettingsLayoutRenderer {
    @Composable override fun HookListFrame(content: @Composable () -> Unit) { UiColumn(content) }

    @Composable override fun HookListHeader(title: String, action: String, onAdd: () -> Unit) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            UiText(title, UiTextRole.Title)
            UiAction(action, onClick = onAdd, role = UiActionRole.Secondary)
        }
    }

    @Composable override fun HookListEntry(label: String, onClick: () -> Unit) { UiAction(label, onClick = onClick) }

    @Composable override fun HookInput(label: String, value: String, autoFocus: Boolean, onChange: (String) -> Unit) {
        UiTextInput(value, onChange, label, autoFocus = autoFocus)
    }

    @Composable override fun HookTypeChoice(label: String, checked: Boolean, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = { onClick() }),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Checkbox(checked, onCheckedChange = null)
            Text(label)
        }
    }

    @Composable override fun HookActions(content: @Composable () -> Unit) { UiActions(content) }

    @Composable override fun HookAction(label: String, role: HookActionRole, autoFocus: Boolean, onClick: () -> Unit) {
        UiAction(
            label, onClick = onClick, autoFocus = autoFocus,
            role = when (role) {
                HookActionRole.Primary -> UiActionRole.Primary
                HookActionRole.Danger, HookActionRole.ProminentDanger -> UiActionRole.Destructive
                HookActionRole.Normal -> UiActionRole.Normal
            },
        )
    }
}
