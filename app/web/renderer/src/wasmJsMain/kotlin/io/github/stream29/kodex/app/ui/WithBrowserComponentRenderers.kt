package io.github.stream29.kodex.app.ui

import androidx.compose.runtime.Composable

@Composable
public actual fun WithBrowserComponentRenderers(content: @Composable () -> Unit) {
    io.github.stream29.kodex.app.ui.WithUiRenderer(io.github.stream29.kodex.app.ui.BrowserUiRenderer) {
        io.github.stream29.kodex.cli.accountusage.WithAccountUsageLayoutRenderer(io.github.stream29.kodex.cli.accountusage.BrowserAccountUsageLayoutRenderer) {
            io.github.stream29.kodex.cli.authenticationsettings.WithAuthenticationSettingsLayoutRenderer(io.github.stream29.kodex.cli.authenticationsettings.BrowserAuthenticationSettingsLayoutRenderer) {
                io.github.stream29.kodex.cli.settings.WithContextSourceSettingsLayoutRenderer(io.github.stream29.kodex.cli.settings.BrowserContextSourceSettingsLayoutRenderer) {
                    io.github.stream29.kodex.cli.settings.WithHookSettingsLayoutRenderer(io.github.stream29.kodex.cli.settings.BrowserHookSettingsLayoutRenderer) {
                        io.github.stream29.kodex.cli.settings.WithMcpSettingsLayoutRenderer(io.github.stream29.kodex.cli.settings.BrowserMcpSettingsLayoutRenderer) {
                            io.github.stream29.kodex.cli.settings.WithLoginLayoutRenderer(io.github.stream29.kodex.cli.settings.BrowserLoginLayoutRenderer) {
                                io.github.stream29.kodex.cli.sessionsettings.WithSessionSettingsLayoutRenderer(io.github.stream29.kodex.cli.sessionsettings.BrowserSessionSettingsLayoutRenderer) {
                                    io.github.stream29.kodex.cli.usagereset.WithUsageResetLayoutRenderer(io.github.stream29.kodex.cli.usagereset.BrowserUsageResetLayoutRenderer) {
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
