package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.mcp.contract.McpOAuthConfiguration
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.openai.codexclistorage.CodexCliMcpAuth
import io.github.stream29.kodex.openai.codexclistorage.CodexCliMcpServer
import kotlin.test.assertEquals
import kotlin.test.assertIs

val kodexMcpConfigurationStoreTest by testSuite {
    test("imports Codex OAuth declarations without a pre-registered client id") {
        val configuration = CodexCliMcpServer.StreamableHttp(
            url = "https://oauth.example.test/mcp",
            auth = CodexCliMcpAuth.OAuth,
            scopes = listOf("tools.read"),
        ).toKodexMcpConfiguration()

        val http = assertIs<McpServerConfiguration.StreamableHttp>(configuration)
        val oauth = assertIs<McpOAuthConfiguration.Uninitialized>(http.oauth)
        assertEquals(null, oauth.client.clientId)
        assertEquals(listOf("tools.read"), oauth.scopes)
    }
}
