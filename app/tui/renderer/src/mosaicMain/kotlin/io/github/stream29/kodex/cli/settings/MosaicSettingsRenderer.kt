package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.*
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Arrangement
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.SubcomposeLayout
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.app.ui.SettingsRenderer
import io.github.stream29.kodex.app.ui.UiActionRole
import io.github.stream29.kodex.app.ui.UiChoiceOption
import io.github.stream29.kodex.cli.components.TextInput
import io.github.stream29.kodex.cli.components.TextInputLayout
import io.github.stream29.kodex.cli.components.TextInputState
import io.github.stream29.kodex.cli.components.TextInputValue
import io.github.stream29.kodex.cli.components.TuiCheckbox
import io.github.stream29.kodex.cli.components.TuiDropdownMenu
import io.github.stream29.kodex.cli.components.TuiDropdownState
import io.github.stream29.kodex.cli.components.TuiDropdownTrigger
import io.github.stream29.kodex.cli.components.TuiInteractionStyle
import io.github.stream29.kodex.cli.components.TuiPopupMenuItem
import io.github.stream29.kodex.cli.components.TuiTheme

/** Retains only field presentation and existing Native anchors; menus remain in the caller's BoxScope. */
internal class MosaicSettingsRenderer(
    private val choices: Map<String, TuiDropdownState> = emptyMap(),
    private val inlineTriggerKeys: Set<String> = emptySet(),
) : SettingsRenderer {
    private val menus = mutableStateMapOf<String, ChoiceBinding>()

    @Composable
    override fun Section(title: String, content: @Composable () -> Unit) {
        SettingsSection(title = title, content = content)
    }

    @Composable
    override fun SectionHeader(title: String, actions: @Composable () -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth().background(SettingsSectionHeaderBackground),
            horizontalArrangement = Arrangement.spacedBy(1),
        ) {
            Text(title, color = SettingsForeground, textStyle = TuiTheme.typography.title)
            actions()
        }
    }

    @Composable
    override fun Item(label: String, supportingText: String?, enabled: Boolean, content: @Composable () -> Unit) {
        SettingsItem(label = label, supportingText = supportingText, enabled = enabled) { content() }
    }

    @Composable
    override fun Entry(label: String, enabled: Boolean, onClick: () -> Unit) {
        SettingsContentButton(label = label, modifier = Modifier.fillMaxWidth(), enabled = enabled, onClick = onClick)
    }

    @Composable
    override fun Action(
        label: String,
        onClick: () -> Unit,
        enabled: Boolean,
        autoFocus: Boolean,
        role: UiActionRole,
    ) {
        when (role) {
            UiActionRole.Primary -> SettingsPrimaryButton(
                label, enabled = enabled, autoFocus = autoFocus, onClick = onClick,
            )
            UiActionRole.Destructive -> SettingsDangerButton(
                label, enabled = enabled, autoFocus = autoFocus, onClick = onClick,
            )
            UiActionRole.Normal, UiActionRole.Secondary -> SettingsActionButton(
                label, enabled = enabled, autoFocus = autoFocus, onClick = onClick,
            )
        }
    }

    @Composable
    override fun Toggle(
        label: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
        enabled: Boolean,
        supportingText: String?,
    ) {
        SettingsCheckboxItem(
            label, checked, onChange, enabled = enabled, supportingText = supportingText,
        )
    }

    @Composable
    override fun InlineToggle(
        label: String,
        contentDescription: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
        enabled: Boolean,
    ) {
        TuiCheckbox(
            label = label,
            checked = checked,
            onCheckedChange = onChange,
            color = SettingsForeground,
            idleTextStyle = TuiTheme.typography.body,
            interactionStyle = TuiInteractionStyle.PreserveColors,
            enabled = enabled,
        )
    }

    @Composable
    override fun TextField(
        token: Any,
        label: String,
        value: String,
        autoFocus: Boolean,
        onChange: (String) -> Unit,
    ) {
        key(token, label) {
            val input = remember { TextInputState(TextInputValue(value)) }
            val focus = remember { FocusRequester() }
            val columns = LocalTerminalState.current.size.columns.coerceAtLeast(1)
            LaunchedEffect(value) {
                if (input.value.text != value) {
                    input.reset(TextInputValue(value, input.value.cursorOffset.coerceAtMost(value.length)))
                }
            }
            Text(label, color = SettingsForeground)
            SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
                val width = minOf(constraints.maxWidth, columns).coerceAtLeast(1)
                val nodes = subcompose(label) {
                    TextInput(
                        input,
                        TextInputLayout.create(input.value, width),
                        Modifier.fillMaxWidth(),
                        focusRequester = focus,
                        autoFocus = autoFocus,
                        onValueChanged = { onChange(it.text) },
                    )
                    LaunchedEffect(autoFocus, focus) {
                        if (autoFocus) {
                            withFrameNanos { }
                            focus.requestFocus()
                        }
                    }
                }.map { it.measure(constraints) }
                layout(nodes.maxOfOrNull { it.width } ?: 0, nodes.maxOfOrNull { it.height } ?: 0) {
                    nodes.forEach { it.place(0, 0) }
                }
            }
        }
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
