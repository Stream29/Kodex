package io.github.stream29.kodex.cli.settings

import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsState
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsStatus
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.mcp.contract.McpAuthenticationState
import io.github.stream29.kodex.mcp.contract.McpClientFailureReason
import io.github.stream29.kodex.mcp.contract.McpImportDecision
import io.github.stream29.kodex.mcp.contract.McpImportItem
import io.github.stream29.kodex.mcp.contract.McpImportItemKind
import io.github.stream29.kodex.mcp.contract.McpImportPreview
import io.github.stream29.kodex.mcp.contract.McpTransportKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpSettingsContentTest {
    @Test
    fun rendersCompactServerButtonsWithoutFlattenedDetailsOrActions() = runTest {
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
                        onOpenDetails = {},
                        onImport = {},
                    )
                }
            }

            assertTrue("MCP servers [Add] [Import from Codex]" in snapshot, snapshot)
            assertTrue("[healthy · Healthy (2 tools)]" in snapshot, snapshot)
            assertTrue("[lost · Failed: Connection lost]" in snapshot, snapshot)
            assertTrue("[disabled · Disabled]" in snapshot, snapshot)
            assertFalse("https://healthy.example.test/mcp" in snapshot, snapshot)
            assertFalse("Headers:" in snapshot, snapshot)
            assertFalse("lost-server --headless" in snapshot, snapshot)
            assertFalse("[Reconnect]" in snapshot, snapshot)
            assertFalse("[Edit]" in snapshot, snapshot)
            assertFalse("[Delete]" in snapshot, snapshot)
            assertFalse("secret" in snapshot, snapshot)
        }
    }

    @Test
    fun rendersServerDetailsAndActionsInsideDialog() = runTest {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Box {
                    TuiPopupHost(modifier = Modifier.width(80).height(24)) {
                        McpServerDetailsDialog(
                            server = McpServerSettingsState(
                                serverName = "lost",
                                transport = McpTransportKind.StreamableHttp,
                                enabled = true,
                                authentication = McpAuthenticationState.LoginRequired,
                                status = McpServerSettingsStatus.Failed(
                                    McpClientFailureReason.ConnectionLost,
                                ),
                                headerNames = listOf("Authorization"),
                                streamableHttpUrl = "https://lost.example.test/mcp",
                            ),
                            onDismiss = {},
                            onEdit = {},
                            onDelete = {},
                            onSetEnabled = {},
                            onLogin = {},
                            onCancelLogin = {},
                            onLogout = {},
                            onReconnect = {},
                        )
                    }
                }
            }

            assertTrue("Transport: Streamable HTTP" in snapshot, snapshot)
            assertTrue("Status: Failed: Connection lost" in snapshot, snapshot)
            assertTrue("Authentication: Login required" in snapshot, snapshot)
            assertTrue("URL: https://lost.example.test/mcp" in snapshot, snapshot)
            assertTrue("Headers: Authorization (values hidden)" in snapshot, snapshot)
            assertTrue("[Disable] [Edit] [Delete]" in snapshot, snapshot)
            assertTrue("[Close] [Log in] [Reconnect]" in snapshot, snapshot)
        }
    }

    @Test
    fun importDefaultsSelectEverySupportedServerAndReplaceConflicts() {
        val preview = importPreview()

        assertEquals(
            mapOf(
                "new" to McpImportDecision.Import,
                "conflict" to McpImportDecision.Replace,
                "unsupported" to McpImportDecision.Skip,
            ),
            preview.defaultImportDecisions(),
        )
    }

    @Test
    fun importDialogShowsLoadedSelectionWithoutPreviewStep() = runTest {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Box {
                    TuiPopupHost(modifier = Modifier.width(80).height(24)) {
                        McpImportDialog(
                            preview = importPreview(),
                            onApply = { _, _ -> },
                            onDismiss = {},
                        )
                    }
                }
            }

            assertTrue("All supported servers are selected." in snapshot, snapshot)
            assertTrue("[✓ new New]" in snapshot, snapshot)
            assertTrue("[✓ conflict Replace existing]" in snapshot, snapshot)
            assertTrue("[– unsupported Unsupported]" in snapshot, snapshot)
            assertTrue("[Import selected (2)]" in snapshot, snapshot)
            assertFalse("[Preview]" in snapshot, snapshot)
        }
    }

    private fun importPreview(): McpImportPreview =
        McpImportPreview(
            id = 1,
            filter = "",
            items = listOf(
                McpImportItem(
                    serverName = "new",
                    transport = McpTransportKind.StreamableHttp,
                    kind = McpImportItemKind.New,
                    enabled = true,
                    selectable = true,
                ),
                McpImportItem(
                    serverName = "conflict",
                    transport = McpTransportKind.Stdio,
                    kind = McpImportItemKind.Conflict,
                    enabled = true,
                    selectable = true,
                ),
                McpImportItem(
                    serverName = "unsupported",
                    transport = null,
                    kind = McpImportItemKind.Unsupported,
                    enabled = null,
                    selectable = false,
                    detail = "Unsupported Codex configuration.",
                ),
            ),
        )
}
