package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.RowScope
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.cli.components.TuiDropdownState
import io.github.stream29.kodex.cli.components.TuiTheme

/** Framework-only field/trigger shared by Settings and independently rendered editors. */
@Composable
public expect fun SettingsDropdownField(
    label: String,
    selectedLabel: String,
    dropdownState: TuiDropdownState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingText: String? = null,
): Unit

@Composable
public expect fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
): Unit

@Composable
public expect fun SettingsItem(
    label: String,
    supportingText: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
): Unit

@Composable
public expect fun SettingsCheckboxItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supportingText: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
): Unit

@Composable
public expect fun SettingsErrorText(value: String): Unit

@Composable
public expect fun SettingsActionButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    autoFocus: Boolean = false,
    onClick: () -> Unit,
): Unit

@Composable
public expect fun SettingsPrimaryButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    autoFocus: Boolean = false,
    onClick: () -> Unit,
): Unit

@Composable
public expect fun SettingsDangerButton(
    label: String,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    enabled: Boolean = true,
    autoFocus: Boolean = false,
    onClick: () -> Unit,
): Unit

@Composable
public expect fun SettingsContentButton(
    label: String,
    modifier: Modifier = Modifier,
    idleTextStyle: TextStyle = TuiTheme.typography.label,
    enabled: Boolean = true,
    autoFocus: Boolean = false,
    onClick: () -> Unit,
): Unit

@Composable
public expect fun SettingsNavigationButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
): Unit

@get:Composable
@get:ReadOnlyComposable
public expect val SettingsForeground: Color

@get:Composable
@get:ReadOnlyComposable
public expect val SettingsSupportingForeground: Color

@get:Composable
@get:ReadOnlyComposable
public expect val SettingsActionForeground: Color

@get:Composable
@get:ReadOnlyComposable
public expect val SettingsErrorForeground: Color

@get:Composable
@get:ReadOnlyComposable
public expect val SettingsHeaderBackground: Color

@get:Composable
@get:ReadOnlyComposable
public expect val SettingsNavigationBackground: Color

@get:Composable
@get:ReadOnlyComposable
public expect val SettingsHomeBackground: Color

@get:Composable
@get:ReadOnlyComposable
public expect val SettingsDialogBackground: Color

@get:Composable
@get:ReadOnlyComposable
public expect val SettingsFieldBackground: Color

@get:Composable
@get:ReadOnlyComposable
public expect val SettingsSectionHeaderBackground: Color

@get:Composable
@get:ReadOnlyComposable
public expect val SettingsActionBackground: Color

@get:Composable
@get:ReadOnlyComposable
public expect val PopupMenuBackground: Color
