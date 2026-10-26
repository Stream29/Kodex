package io.github.stream29.kodex.cli.usagereset

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import io.github.stream29.kodex.cli.components.TuiDialogActionRow
import io.github.stream29.kodex.cli.settings.SettingsActionBackground
import io.github.stream29.kodex.cli.settings.SettingsActionButton
import io.github.stream29.kodex.cli.settings.SettingsContentButton
import io.github.stream29.kodex.cli.settings.SettingsPrimaryButton

internal object MosaicUsageResetLayoutRenderer : UsageResetLayoutRenderer {
    @Composable
    override fun UsageResetOptionButton(label: String, enabled: Boolean, onClick: () -> Unit) {
        SettingsContentButton(label, modifier = Modifier.fillMaxWidth(), enabled = enabled, onClick = onClick)
    }

    @Composable
    override fun UsageResetAction(label: String, primary: Boolean, autoFocus: Boolean, onClick: () -> Unit) {
        if (primary) SettingsPrimaryButton(label, autoFocus = autoFocus, onClick = onClick)
        else SettingsActionButton(label, autoFocus = autoFocus, onClick = onClick)
    }

    @Composable
    override fun UsageResetActions(content: @Composable () -> Unit) {
        TuiDialogActionRow(Modifier.fillMaxWidth().background(SettingsActionBackground)) { content() }
    }
}
