package io.github.stream29.kodex.mcp.impl

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.mcp.contract.McpOAuthClient
import io.github.stream29.kodex.mcp.contract.McpOAuthConfiguration
import io.github.stream29.kodex.mcp.contract.McpSecret
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

val mcpBackendPublicApiTest by testSuite {
    test("prepared OAuth login retains its public values and exchange callback") {
        val configuration = McpOAuthConfiguration.Uninitialized(
            client = McpOAuthClient(clientId = "test-client"),
            resource = "https://mcp.example.test",
            scopes = listOf("tools"),
        )
        var exchangedCode: String? = null
        val prepared = PreparedMcpOAuthLogin(
            preparedConfiguration = configuration,
            state = "state",
            authorizationUrl = "https://auth.example.test/authorize",
            exchange = { code ->
                exchangedCode = code
                McpOAuthConfiguration.Initialized(
                    client = configuration.client,
                    resource = configuration.resource,
                    scopes = configuration.scopes,
                    resolvedAuthorizationEndpoint = "https://auth.example.test/authorize",
                    resolvedTokenEndpoint = "https://auth.example.test/token",
                    accessToken = McpSecret("test-token"),
                )
            },
        )
        val preparer = McpOAuthLoginPreparer { prepared }

        runTest {
            assertSame(
                prepared,
                preparer.prepare(
                    McpServerConfiguration.StreamableHttp(
                        url = "https://mcp.example.test",
                        oauth = configuration,
                    ),
                ),
            )
            assertEquals(configuration, prepared.preparedConfiguration)
            assertEquals("state", prepared.state)
            assertEquals("https://auth.example.test/authorize", prepared.authorizationUrl)
            assertNull(exchangedCode)
            assertEquals(
                "test-token",
                prepared.exchangeCode("authorization-code").accessToken.value,
            )
            assertEquals("authorization-code", exchangedCode)
        }
    }
}
