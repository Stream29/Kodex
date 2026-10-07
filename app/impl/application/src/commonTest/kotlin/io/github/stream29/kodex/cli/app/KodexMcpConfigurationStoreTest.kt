package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.test.withRpcFrontend
import io.github.stream29.kodex.mcp.contract.*
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlin.test.*

val kodexMcpConfigurationStoreTest by testSuite {
    test("backend import still preserves OAuth without a pre-registered client id") {
        withRpcFrontend {
            SystemCoroutineFileSystem.createDirectories(Path(root, "codex"))
            SystemCoroutineFileSystem.writeString(Path(root, "codex/config.toml"), """
                [mcp_servers.example]
                url = "https://oauth.example.test/mcp"
                scopes = ["tools.read"]
            """.trimIndent())
            val imported = services.global.getCodexMcpSettings()
            val http = assertIs<McpServerConfiguration.StreamableHttp>(
                assertIs<McpCodexImportCandidate.Supported>(imported.single()).configuration,
            )
            val oauth = assertIs<McpOAuthConfiguration.Uninitialized>(http.oauth)
            assertNull(oauth.client.clientId)
            assertEquals(listOf("tools.read"), oauth.scopes)
        }
    }
}
