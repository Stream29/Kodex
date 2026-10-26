package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.*
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.Row
import io.github.stream29.kodex.app.ui.SettingsRenderer
import io.github.stream29.kodex.app.ui.UiChoiceOption
import io.github.stream29.kodex.cli.components.TuiDropdownMenu
import io.github.stream29.kodex.cli.components.TuiDropdownState
import io.github.stream29.kodex.cli.components.TuiDropdownTrigger
import io.github.stream29.kodex.cli.components.TuiPopupMenuItem

/** Retains only field presentation and existing Native anchors; menus remain in the caller's BoxScope. */
internal class MosaicSettingsRenderer(
    private val choices: Map<String, TuiDropdownState>,
    private val inlineTriggerKeys: Set<String> = emptySet(),
) : SettingsRenderer {
    private val menus = mutableStateMapOf<String, ChoiceBinding>()

    @Composable
    override fun Section(title: String, content: @Composable () -> Unit) {
        SettingsSection(title = title, content = content)
    }

    @Composable
    override fun Item(label: String, supportingText: String?, enabled: Boolean, content: @Composable () -> Unit) {
        SettingsItem(label = label, supportingText = supportingText, enabled = enabled) { content() }
    }

    @Composable
    override fun Action(label: String, onClick: () -> Unit, enabled: Boolean, autoFocus: Boolean) {
        SettingsActionButton(label, onClick = onClick, enabled = enabled, autoFocus = autoFocus)
    }

    @Composable
    override fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean, supportingText: String?) {
        SettingsCheckboxItem(
            label, checked, onChange, enabled = enabled, supportingText = supportingText,
        )
    }

    @Composable
    override fun Choice(
        fieldKey: String,
        label: String,
        options: List<UiChoiceOption>,
        selectedKey: String,
        onSelect: (String) -> Unit,
        enabled: Boolean,
        supportingText: String?,
    ) {
        key(fieldKey) {
            val dropdown = requireNotNull(choices[fieldKey]) { "Missing Native choice anchor: $fieldKey" }
            val projection = rememberUpdatedState(ChoiceProjection(options, selectedKey, onSelect, enabled))
            val binding = remember(dropdown) { ChoiceBinding(dropdown, projection) }
            DisposableEffect(fieldKey, binding) {
                menus[fieldKey] = binding
                onDispose {
                    dropdown.dismiss()
                    if (menus[fieldKey] === binding) menus.remove(fieldKey)
                }
            }
            LaunchedEffect(enabled, options.isEmpty()) { if (!enabled || options.isEmpty()) dropdown.dismiss() }
            val selectedLabel = options.firstOrNull { it.key == selectedKey }?.label ?: selectedKey
            if (fieldKey in inlineTriggerKeys) Row {
                Text("$label: ")
                TuiDropdownTrigger(dropdown, selectedLabel, enabled = enabled && options.isNotEmpty(), autoFocus = true)
            } else SettingsDropdownField(
                label = label,
                selectedLabel = selectedLabel,
                dropdownState = dropdown,
                enabled = enabled && options.isNotEmpty(),
                supportingText = supportingText,
            )
        }
    }

    @Composable
    fun BoxScope.Menus() {
        menus.values.forEach { binding ->
            key(binding) {
                val projection by binding.projection
                TuiDropdownMenu(
                    dropdownState = binding.dropdown,
                    backgroundColor = PopupMenuBackground,
                ) {
                    projection.options.forEach { option ->
                        TuiPopupMenuItem(
                            key = option.key, selected = option.key == projection.selectedKey,
                            enabled = projection.enabled,
                            onClick = { if (projection.enabled) projection.onSelect(option.key) },
                        ) { Text(option.label) }
                    }
                }
            }
        }
    }
}

private class ChoiceBinding(val dropdown: TuiDropdownState, val projection: State<ChoiceProjection>)
private data class ChoiceProjection(
    val options: List<UiChoiceOption>, val selectedKey: String, val onSelect: (String) -> Unit, val enabled: Boolean,
)
