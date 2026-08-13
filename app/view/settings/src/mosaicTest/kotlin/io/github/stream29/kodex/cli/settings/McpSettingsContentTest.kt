package io.github.stream29.kodex.cli.settings

import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsState
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsStatus
import io.github.stream29.kodex.mcp.contract.McpClientFailureReason
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
                                "healthy",
                                McpServerSettingsStatus.Healthy(toolCount = 2),
                            ),
                            McpServerSettingsState(
                                "lost",
                                McpServerSettingsStatus.Failed(
                                    McpClientFailureReason.ConnectionLost,
                                ),
                            ),
                            McpServerSettingsState(
                                "disabled",
                                McpServerSettingsStatus.Disabled,
                            ),
                        ),
                        onReconnect = {},
                    )
                }
            }

            assertTrue("healthy: Healthy (2 tools)" in snapshot, snapshot)
            assertTrue("lost: Failed: Connection lost [Reconnect]" in snapshot, snapshot)
            assertTrue("disabled: Disabled" in snapshot, snapshot)
            assertFalse("secret" in snapshot, snapshot)
        }
    }
}
