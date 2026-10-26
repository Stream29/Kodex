package io.github.stream29.kodex.app.ui

import androidx.compose.runtime.Composable

@Composable
public actual fun WithMosaicComponentRenderers(content: @Composable () -> Unit) {
    io.github.stream29.kodex.app.ui.WithUiRenderer(io.github.stream29.kodex.app.ui.MosaicUiRenderer) {
        io.github.stream29.kodex.cli.accountusage.WithAccountUsageLayoutRenderer(io.github.stream29.kodex.cli.accountusage.MosaicAccountUsageLayoutRenderer) {
            io.github.stream29.kodex.cli.authenticationsettings.WithAuthenticationSettingsLayoutRenderer(io.github.stream29.kodex.cli.authenticationsettings.MosaicAuthenticationSettingsLayoutRenderer) {
                io.github.stream29.kodex.cli.settings.WithContextSourceSettingsLayoutRenderer(io.github.stream29.kodex.cli.settings.MosaicContextSourceSettingsLayoutRenderer) {
                    io.github.stream29.kodex.cli.settings.WithHookSettingsLayoutRenderer(io.github.stream29.kodex.cli.settings.MosaicHookSettingsLayoutRenderer) {
                        io.github.stream29.kodex.cli.settings.WithMcpSettingsLayoutRenderer(io.github.stream29.kodex.cli.settings.MosaicMcpSettingsLayoutRenderer) {
                            io.github.stream29.kodex.cli.settings.WithLoginLayoutRenderer(io.github.stream29.kodex.cli.settings.MosaicLoginLayoutRenderer) {
                                io.github.stream29.kodex.cli.sessionsettings.WithSessionSettingsLayoutRenderer(io.github.stream29.kodex.cli.sessionsettings.MosaicSessionSettingsLayoutRenderer) {
                                    io.github.stream29.kodex.cli.usagereset.WithUsageResetLayoutRenderer(io.github.stream29.kodex.cli.usagereset.MosaicUsageResetLayoutRenderer) {
                                        content()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
