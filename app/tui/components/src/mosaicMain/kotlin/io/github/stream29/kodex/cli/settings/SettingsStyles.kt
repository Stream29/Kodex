package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.RowScope
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiCheckbox
import io.github.stream29.kodex.cli.components.TuiInteractionStyle
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.TuiDropdownState
import io.github.stream29.kodex.cli.components.TuiDropdownTrigger

@Composable
public actual fun SettingsDropdownField(
    label: String,
    selectedLabel: String,
    dropdownState: TuiDropdownState,
    modifier: Modifier,
    enabled: Boolean,
    supportingText: String?,
) {
    SettingsItem(label, supportingText, modifier, enabled) {
        TuiDropdownTrigger(
            dropdownState = dropdownState,
            label = selectedLabel,
            color = SettingsForeground,
            interactionStyle = TuiInteractionStyle.PreserveColors,
            enabled = enabled,
        )
    }
}

@Composable
public actual fun SettingsSection(
    title: String,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth().background(SettingsFieldBackground)) {
        Text(
            value = title,
            modifier = Modifier.fillMaxWidth().background(SettingsSectionHeaderBackground),
            color = SettingsForeground,
            textStyle = TuiTheme.typography.title,
        )
        content()
    }
}

@Composable
public actual fun SettingsItem(
    label: String,
    supportingText: String?,
    modifier: Modifier,
    enabled: Boolean,
    trailing: @Composable RowScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth().background(SettingsFieldBackground)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                value = label,
                color = if (enabled) SettingsForeground else SettingsSupportingForeground,
                textStyle = SettingsItemTextStyle,
            )
            Text(" ")
            trailing()
        }
        supportingText?.let { text ->
            Text(
                value = text,
                modifier = Modifier.fillMaxWidth(),
                color = SettingsSupportingForeground,
                textStyle = TuiTheme.typography.supporting,
            )
        }
    }
}

@Composable
public actual fun SettingsCheckboxItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supportingText: String?,
    modifier: Modifier,
    enabled: Boolean,
) {
    Column(modifier = modifier.fillMaxWidth().background(SettingsFieldBackground)) {
        TuiCheckbox(
            label = label,
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.fillMaxWidth(),
            color = SettingsForeground,
            idleTextStyle = SettingsItemTextStyle,
            interactionStyle = TuiInteractionStyle.PreserveColors,
            enabled = enabled,
        )
        supportingText?.let { text ->
            Text(
                value = text,
                modifier = Modifier.fillMaxWidth(),
                color = SettingsSupportingForeground,
                textStyle = TuiTheme.typography.supporting,
            )
        }
    }
}

@Composable
public actual fun SettingsErrorText(value: String) {
    Text(
        value = value,
        color = SettingsErrorForeground,
        textStyle = TuiTheme.typography.body + TextStyle.Bold,
    )
}

@Composable
public actual fun SettingsActionButton(
    label: String,
    modifier: Modifier,
    enabled: Boolean,
    autoFocus: Boolean,
    onClick: () -> Unit,
) {
    SettingsButton(
        label = label,
        modifier = modifier,
        contentColor = TuiTheme.colorScheme.primary,
        enabled = enabled,
        autoFocus = autoFocus,
        onClick = onClick,
    )
}

@Composable
public actual fun SettingsPrimaryButton(
    label: String,
    modifier: Modifier,
    enabled: Boolean,
    autoFocus: Boolean,
    onClick: () -> Unit,
) {
    SettingsButton(
        label = label,
        modifier = modifier,
        containerColor = TuiTheme.colorScheme.primary,
        contentColor = TuiTheme.colorScheme.onPrimary,
        enabled = enabled,
        autoFocus = autoFocus,
        onClick = onClick,
    )
}

@Composable
public actual fun SettingsDangerButton(
    label: String,
    modifier: Modifier,
    prominent: Boolean,
    enabled: Boolean,
    autoFocus: Boolean,
    onClick: () -> Unit,
) {
    SettingsButton(
        label = label,
        modifier = modifier,
        containerColor = if (prominent) TuiTheme.colorScheme.error else null,
        contentColor = if (prominent) {
            TuiTheme.colorScheme.onError
        } else {
            TuiTheme.colorScheme.error
        },
        enabled = enabled,
        autoFocus = autoFocus,
        onClick = onClick,
    )
}

@Composable
public actual fun SettingsContentButton(
    label: String,
    modifier: Modifier,
    idleTextStyle: TextStyle,
    enabled: Boolean,
    autoFocus: Boolean,
    onClick: () -> Unit,
) {
    SettingsButton(
        label = label,
        modifier = modifier,
        contentColor = TuiTheme.colorScheme.onSurface,
        idleTextStyle = idleTextStyle,
        enabled = enabled,
        autoFocus = autoFocus,
        onClick = onClick,
    )
}

@Composable
public actual fun SettingsNavigationButton(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    SettingsButton(
        label = label,
        modifier = modifier,
        containerColor = if (selected) TuiTheme.colorScheme.secondaryContainer else null,
        contentColor = if (selected) {
            TuiTheme.colorScheme.onSecondaryContainer
        } else {
            TuiTheme.colorScheme.onSurface
        },
        selected = selected,
        onClick = onClick,
    )
}

@Composable
private fun SettingsButton(
    label: String,
    contentColor: Color,
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    idleTextStyle: TextStyle = TuiTheme.typography.label,
    selected: Boolean = false,
    enabled: Boolean = true,
    autoFocus: Boolean = false,
    onClick: () -> Unit,
) {
    val resolvedContainer = when {
        enabled -> containerColor
        containerColor != null -> TuiTheme.colorScheme.surfaceContainerHighest
        else -> null
    }
    val resolvedContent = if (enabled) contentColor else TuiTheme.colorScheme.onSurface
    val resolvedModifier = resolvedContainer?.let { color ->
        modifier.background(color)
    } ?: modifier
    TuiButton(
        label = label,
        modifier = resolvedModifier,
        color = resolvedContent,
        idleTextStyle = idleTextStyle,
        interactionStyle = TuiInteractionStyle.PreserveColors,
        selected = selected,
        enabled = enabled,
        autoFocus = autoFocus,
        onClick = onClick,
    )
}

private val SettingsItemTextStyle: TextStyle
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.typography.body + TextStyle.Bold

public actual val SettingsForeground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.onSurface

public actual val SettingsSupportingForeground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.onSurfaceVariant

public actual val SettingsActionForeground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.primary

public actual val SettingsErrorForeground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.error

public actual val SettingsHeaderBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surfaceContainerHigh

public actual val SettingsNavigationBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = Color.Unspecified

public actual val SettingsHomeBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surface

public actual val SettingsDialogBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surfaceContainer

public actual val SettingsFieldBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surface

public actual val SettingsSectionHeaderBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surfaceContainerHigh

public actual val SettingsActionBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surfaceContainerHigh

public actual val PopupMenuBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surfaceContainer
