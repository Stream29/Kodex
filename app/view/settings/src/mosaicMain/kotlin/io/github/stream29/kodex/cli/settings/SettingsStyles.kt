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
import io.github.stream29.kodex.cli.components.TuiCheckbox
import io.github.stream29.kodex.cli.components.TuiTheme

@Composable
internal fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
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
internal fun SettingsItem(
    label: String,
    supportingText: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
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
internal fun SettingsCheckboxItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supportingText: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth().background(SettingsFieldBackground)) {
        TuiCheckbox(
            label = label,
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.fillMaxWidth(),
            color = if (enabled) SettingsForeground else SettingsSupportingForeground,
            idleTextStyle = SettingsItemTextStyle,
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
internal fun SettingsErrorText(value: String) {
    Text(
        value = value,
        color = SettingsErrorForeground,
        textStyle = TuiTheme.typography.body + TextStyle.Bold,
    )
}

private val SettingsItemTextStyle: TextStyle
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.typography.body + TextStyle.Bold

internal val SettingsForeground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.onSurface

internal val SettingsSupportingForeground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.onSurfaceVariant

internal val SettingsActionForeground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.primary

internal val SettingsErrorForeground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.error

internal val SettingsHeaderBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surfaceContainerHigh

internal val SettingsNavigationBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surfaceContainer

internal val SettingsHomeBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surface

internal val SettingsFieldBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surface

internal val SettingsSectionHeaderBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surfaceContainerHigh

internal val SettingsActionBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surfaceContainerHigh

internal val PopupMenuBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surfaceContainer
