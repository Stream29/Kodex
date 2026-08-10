package io.github.stream29.kodex.cli.app

import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.mcp.contract.McpClient
import io.github.stream29.kodex.mcp.contract.McpClientFailureReason
import io.github.stream29.kodex.mcp.contract.McpClientState
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.contract.McpTool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpSettingsContentTest {
    @Test
    fun rendersObservableSanitizedStatusesAndFailedReconnectAction() = runTest {
        val healthy = TestMcpClient("healthy", McpClientState.Healthy)
        val failed = TestMcpClient(
            "lost",
            McpClientState.Failed(McpClientFailureReason.ConnectionLost),
        )
        val configurations = mapOf(
            "healthy" to McpServerConfiguration.StreamableHttp(
                url = "https://secret.example.test/mcp?token=secret-token",
                headers = mapOf("Authorization" to "secret-header"),
            ),
            "lost" to McpServerConfiguration.Stdio(
                command = "secret-command",
                environment = mapOf("SECRET" to "secret-environment"),
            ),
            "disabled" to McpServerConfiguration.Stdio(
                command = "disabled-secret-command",
                enabled = false,
            ),
            "starting" to McpServerConfiguration.StreamableHttp(
                url = "https://starting-secret.example.test/mcp",
            ),
        )

        runMosaicTest {
            val failedSnapshot = setContentAndSnapshot {
                Column(Modifier.width(80)) {
                    McpSettingsContent(
                        configurations = configurations,
                        clients = mapOf("healthy" to healthy, "lost" to failed),
                        onReconnect = {},
                    )
                }
            }

            assertTrue("healthy: Healthy (0 tools)" in failedSnapshot, failedSnapshot)
            assertTrue("lost: Failed: Connection lost [Reconnect]" in failedSnapshot, failedSnapshot)
            assertTrue("disabled: Disabled" in failedSnapshot, failedSnapshot)
            assertTrue("starting: Connecting" in failedSnapshot, failedSnapshot)
            assertFalse("secret" in failedSnapshot, failedSnapshot)

            failed.state.value = McpClientState.Connecting
            val connectingSnapshot = awaitSnapshot()
            assertTrue("lost: Connecting" in connectingSnapshot, connectingSnapshot)
            assertFalse("[Reconnect]" in connectingSnapshot, connectingSnapshot)
        }
    }
}

private class TestMcpClient(
    override val serverName: String,
    initialState: McpClientState,
) : McpClient {
    override val state = MutableStateFlow(initialState)

    override fun listTools(): List<McpTool> = emptyList()

    override suspend fun reconnect() = Unit
}
