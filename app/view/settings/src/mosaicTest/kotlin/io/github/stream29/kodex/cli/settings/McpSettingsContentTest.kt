package io.github.stream29.kodex.cli.settings

import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsState
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsStatus
import io.github.stream29.kodex.mcp.contract.McpAuthenticationState
import io.github.stream29.kodex.mcp.contract.McpClientFailureReason
import io.github.stream29.kodex.mcp.contract.McpTransportKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpSettingsContentTest {
    @Test
    fun rendersOnlySanitizedStatusesAndFailedReconnectAction() = runTest {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(80)) {
                    McpSettingsContent(
                        servers = listOf(
                            McpServerSettingsState(
                                serverName = "healthy",
                                transport = McpTransportKind.StreamableHttp,
                                enabled = true,
                                authentication = McpAuthenticationState.NotConfigured,
                                status = McpServerSettingsStatus.Healthy(toolCount = 2),
                                headerNames = listOf("Authorization"),
                                streamableHttpUrl = "https://healthy.example.test/mcp",
                            ),
                            McpServerSettingsState(
                                serverName = "lost",
                                transport = McpTransportKind.Stdio,
                                enabled = true,
                                authentication = McpAuthenticationState.NotConfigured,
                                status = McpServerSettingsStatus.Failed(
                                    McpClientFailureReason.ConnectionLost,
                                ),
                                stdioCommand = "lost-server",
                                stdioArguments = listOf("--headless"),
                            ),
                            McpServerSettingsState(
                                serverName = "disabled",
                                transport = McpTransportKind.Stdio,
                                enabled = false,
                                authentication = McpAuthenticationState.NotConfigured,
                                status = McpServerSettingsStatus.Disabled,
                            ),
                        ),
                        onAdd = {},
                        onEdit = {},
                        onDelete = {},
                        onSetEnabled = { _, _ -> },
                        onLogin = {},
                        onCancelLogin = {},
                        onLogout = {},
                        onImport = {},
                        onReconnect = {},
                    )
                }
            }

            assertTrue("healthy · Streamable HTTP · Healthy (2 tools)" in snapshot, snapshot)
            assertTrue("https://healthy.example.test/mcp" in snapshot, snapshot)
            assertTrue("Headers: Authorization (values hidden)" in snapshot, snapshot)
            assertTrue("lost · stdio · Failed: Connection lost" in snapshot, snapshot)
            assertTrue("lost-server --headless" in snapshot, snapshot)
            assertTrue("[Reconnect]" in snapshot, snapshot)
            assertTrue("disabled · stdio · Disabled" in snapshot, snapshot)
            assertFalse("secret" in snapshot, snapshot)
        }
    }
}
