package io.github.stream29.kodex.cli.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.stream29.kodex.app.ui.*

internal object BrowserContextSourceSettingsLayoutRenderer : ContextSourceSettingsLayoutRenderer {
    @Composable
    override fun ContextSourceCustomItem(
        path: String, checked: Boolean, onChange: (Boolean) -> Unit, onRemove: () -> Unit,
    ) {
        UiColumn {
            UiText(path)
            UiText("Global context source", UiTextRole.Supporting)
            Row {
                Row(
                    Modifier.toggleable(checked, role = Role.Checkbox, onValueChange = onChange)
                        .semantics { contentDescription = "$path: Enabled" },
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Checkbox(checked, onCheckedChange = null)
                    Text("Enabled")
                }
                UiAction("Remove", onClick = onRemove, role = UiActionRole.Destructive)
            }
        }
    }

    @Composable
    override fun ContextSourcePathInput(value: String, onChange: (String) -> Unit) {
        UiTextInput(value, onChange, "Context source path", autoFocus = true)
    }

    @Composable
    override fun ContextSourceDialogActions(content: @Composable () -> Unit) { UiActions(content) }

    @Composable
    override fun ContextSourceDialogAction(label: String, primary: Boolean, onClick: () -> Unit) {
        UiAction(label, onClick = onClick)
    }
}
