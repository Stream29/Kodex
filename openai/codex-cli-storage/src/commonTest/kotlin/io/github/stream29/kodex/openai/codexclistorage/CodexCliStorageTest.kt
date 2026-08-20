package io.github.stream29.kodex.openai.codexclistorage

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.osenvironment.environmentVariable
import io.github.stream29.kodex.utils.osenvironment.userHomeDirectory
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue


private fun testCodexDirectory(): Path =
    environmentVariable("CODEX_HOME")
        ?.takeIf(String::isNotBlank)
        ?.let(::Path)
        ?: userHomeDirectory()?.let { home -> Path(home, ".codex") }
        ?: error("CODEX_HOME or a readable user home directory must be set for real Codex CLI storage tests.")

val codexCliStorageTest by testSuite {
    test("reads codex auth") {
        val auth = CodexCliStorage(testCodexDirectory()).readAuthOrNull()
            ?: error("Expected Codex CLI auth.")

        val tokens = assertNotNull(auth.tokens, "Expected Codex CLI auth tokens.")
        assertTrue(tokens.accessToken.isNotBlank(), "Expected Codex CLI access token.")
        assertNotNull(tokens.accountId, "Expected Codex CLI ChatGPT account id.")
    }

    test("classifies explicit MCP imports without exposing unsupported values") {
        val root = Path(SystemTemporaryDirectory, "codex-mcp-import-${Random.nextLong()}")
        try {
            SystemCoroutineFileSystem.createDirectories(root)
            SystemCoroutineFileSystem.writeString(
                Path(root, "config.toml"),
                """
                [mcp_servers.docs]
                url = "https://docs.example.test/mcp"

                [mcp_servers.docs.http_headers]
                X-Static = "private-header"

                [mcp_servers.browser]
                command = "browser-mcp"
                args = ["--headless"]

                [mcp_servers.dynamic-auth]
                url = "https://dynamic.example.test/mcp"
                bearer_token_env_var = "PRIVATE_TOKEN_NAME"

                [mcp_servers.dynamic-oauth]
                url = "https://dynamic-oauth.example.test/mcp"
                auth = "oauth"

                [mcp_servers.oauth]
                url = "https://oauth.example.test/mcp"
                scopes = ["tools.read"]
                oauth_resource = "https://oauth.example.test"

                [mcp_servers.oauth.oauth]
                client_id = "oauth-client"

                [mcp_servers.unsupported-oauth]
                url = "https://unsupported-oauth.example.test/mcp"

                [mcp_servers.unsupported-oauth.oauth]
                client_id = "oauth-client"
                client_secret = "nested-private-secret"

                [mcp_servers.ambiguous]
                url = "https://ambiguous.example.test/mcp"
                command = "ambiguous-mcp"
                """.trimIndent(),
            )

            val candidates = CodexCliStorage(root).readMcpImportCandidates()

            assertEquals(
                listOf(
                    "ambiguous",
                    "browser",
                    "docs",
                    "dynamic-auth",
                    "dynamic-oauth",
                    "oauth",
                    "unsupported-oauth",
                ),
                candidates.map(CodexCliMcpImportCandidate::serverName),
            )
            val byName = candidates.associateBy(CodexCliMcpImportCandidate::serverName)
            assertIs<CodexCliMcpImportCandidate.Unsupported>(byName.getValue("ambiguous"))
            assertIs<CodexCliMcpImportCandidate.Supported>(byName.getValue("browser"))
            assertIs<CodexCliMcpImportCandidate.Supported>(byName.getValue("docs"))
            val unsupported =
                assertIs<CodexCliMcpImportCandidate.Unsupported>(
                    byName.getValue("dynamic-auth"),
                )
            assertEquals(CodexCliMcpTransportKind.StreamableHttp, unsupported.transport)
            assertTrue("bearer_token_env_var" in unsupported.detail)
            assertFalse("PRIVATE_TOKEN_NAME" in unsupported.detail)
            val dynamicOAuth = assertIs<CodexCliMcpServer.StreamableHttp>(
                assertIs<CodexCliMcpImportCandidate.Supported>(
                    byName.getValue("dynamic-oauth"),
                ).configuration,
            )
            assertEquals(CodexCliMcpAuth.OAuth, dynamicOAuth.auth)
            assertEquals(null, dynamicOAuth.oauth?.clientId)
            val oauth = assertIs<CodexCliMcpServer.StreamableHttp>(
                assertIs<CodexCliMcpImportCandidate.Supported>(
                    byName.getValue("oauth"),
                ).configuration,
            )
            assertEquals("oauth-client", oauth.oauth?.clientId)
            assertEquals(listOf("tools.read"), oauth.scopes)
            val unsupportedOAuth =
                assertIs<CodexCliMcpImportCandidate.Unsupported>(
                    byName.getValue("unsupported-oauth"),
                )
            assertTrue("client_secret" in unsupportedOAuth.detail)
            assertFalse("nested-private-secret" in unsupportedOAuth.detail)
        } finally {
            deleteRecursively(root)
        }
    }

    test("returns null when a Codex file is absent") {
        val root = Path(SystemTemporaryDirectory, "codex-empty-${Random.nextLong()}")
        try {
            SystemCoroutineFileSystem.createDirectories(root)
            val storage = CodexCliStorage(root)

            assertEquals(null, storage.readAuthOrNull())
            assertEquals(emptyList(), storage.readMcpImportCandidates())
        } finally {
            deleteRecursively(root)
        }
    }

    test("rejects a token object without Codex required JWTs") {
        val root = Path(SystemTemporaryDirectory, "codex-auth-${Random.nextLong()}")
        try {
            SystemCoroutineFileSystem.createDirectories(root)
            SystemCoroutineFileSystem.writeString(
                Path(root, "auth.json"),
                "{\"auth_mode\":\"chatgpt\",\"tokens\":{\"access_token\":\"access\"}}",
            )

            assertFails {
                CodexCliStorage(root).readAuthOrNull()
            }
        } finally {
            deleteRecursively(root)
        }
    }

    test("accepts the chatgptAuthTokens auth mode") {
        val root = Path(SystemTemporaryDirectory, "codex-auth-mode-${Random.nextLong()}")
        try {
            SystemCoroutineFileSystem.createDirectories(root)
            SystemCoroutineFileSystem.writeString(
                Path(root, "auth.json"),
                """
                {
                  "auth_mode":"chatgptAuthTokens",
                  "tokens":{
                    "id_token":"id",
                    "access_token":"access",
                    "refresh_token":"refresh"
                  }
                }
                """.trimIndent(),
            )

            assertEquals(CodexAuthMode.ChatgptAuthTokens, CodexCliStorage(root).readAuthOrNull()?.authMode)
        } finally {
            deleteRecursively(root)
        }
    }
}

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { child -> deleteRecursively(child) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
