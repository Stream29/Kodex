package io.github.stream29.kodex.cli.rpc

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.mcp.contract.McpCodexImportCandidate
import io.github.stream29.kodex.mcp.contract.McpImportDecision
import io.github.stream29.kodex.mcp.contract.McpImportItemKind
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.contract.McpTransportKind
import io.github.stream29.kodex.mcp.contract.ReservedMcpServerNames
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

val rpcMcpSettingsTest by testSuite {
    test("Codex import preview marks reserved server names as unselectable Unsupported and rejects non-Skip commit") {
        val configuration = McpServerConfiguration.Stdio(command = "example-mcp", enabled = true)
        val reservedCandidates = ReservedMcpServerNames.sorted().map { reserved ->
            McpCodexImportCandidate.Supported(serverName = reserved, configuration = configuration)
        }
        val importState = CodexMcpImport(
            id = 1L,
            candidates = reservedCandidates + listOf(
                McpCodexImportCandidate.Supported("new-server", configuration),
                McpCodexImportCandidate.Supported("conflict-server", configuration),
                McpCodexImportCandidate.Unsupported("broken-server", McpTransportKind.Stdio, "Unsupported field."),
            ),
            baseline = mapOf("conflict-server" to configuration),
        )

        val preview = importState.present("")
        ReservedMcpServerNames.forEach { reserved ->
            val item = preview.items.single { it.serverName == reserved }
            assertEquals(McpImportItemKind.Unsupported, item.kind)
            assertEquals(McpTransportKind.Stdio, item.transport)
            assertNull(item.enabled)
            assertFalse(item.selectable)
            assertTrue(!item.detail.isNullOrBlank())
        }

        val newItem = preview.items.single { it.serverName == "new-server" }
        assertEquals(McpImportItemKind.New, newItem.kind)
        assertTrue(newItem.selectable)

        val conflictItem = preview.items.single { it.serverName == "conflict-server" }
        assertEquals(McpImportItemKind.Conflict, conflictItem.kind)
        assertTrue(conflictItem.selectable)

        assertFailsWith<IllegalArgumentException> {
            importState.resolveConfigurations(mapOf("terminal" to McpImportDecision.Import))
        }
        assertFailsWith<IllegalArgumentException> {
            importState.resolveConfigurations(mapOf("terminal" to McpImportDecision.Replace))
        }

        val resolved = importState.resolveConfigurations(
            mapOf(
                "terminal" to McpImportDecision.Skip,
                "new-server" to McpImportDecision.Import,
                "conflict-server" to McpImportDecision.Replace,
            ),
        )
        assertEquals(
            mapOf(
                "new-server" to configuration,
                "conflict-server" to configuration,
            ),
            resolved,
        )
    }
}
