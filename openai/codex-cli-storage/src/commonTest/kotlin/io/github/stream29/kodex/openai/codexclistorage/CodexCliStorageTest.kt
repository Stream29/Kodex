package io.github.stream29.kodex.openai.codexclistorage

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.kodex.hook.contract.HookCodexImportCandidate
import io.github.stream29.kodex.hook.contract.HookCodexSourceKind
import io.github.stream29.kodex.hook.contract.HookMatcher
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
            assertEquals(
                emptyList(),
                storage.readHookImportCandidates(HookCodexSourceKind.User),
            )
        } finally {
            deleteRecursively(root)
        }
    }

    test("explicit Hook import expands supported commands and reports excluded features") {
        val root = Path(SystemTemporaryDirectory, "codex-hook-import-${Random.nextLong()}")
        try {
            SystemCoroutineFileSystem.createDirectories(root)
            SystemCoroutineFileSystem.writeString(
                Path(root, "hooks.json"),
                """
                {
                  "description":"Imported checks",
                  "private_source_field":"must-not-appear",
                  "hooks":{
                    "PreToolUse":[{
                      "matcher":"shell|Bash",
                      "private_group_field":"must-not-appear",
                      "hooks":[{
                        "type":"command",
                        "command":"echo ${'$'}{HOOK_TOKEN}",
                        "commandWindows":"echo windows",
                        "timeout":0,
                        "enabled":true,
                        "key":"check-one",
                        "trusted_hash":"private-trust-hash",
                        "statusMessage":"Checking",
                        "additionalContextLimit":1200,
                        "private_handler_field":"must-not-appear"
                      },{
                        "type":"prompt",
                        "prompt":"private prompt"
                      },{
                        "type":"command",
                        "command":"private async command",
                        "async":true
                      }]
                    }],
                    "SessionStart":[{
                      "hooks":[{"type":"command","command":"private session command"}]
                    }],
                    "state":{
                      "check-one":{
                        "enabled":false,
                        "trusted_hash":"private-state-hash"
                      },
                      "unmatched":{
                        "enabled":true
                      }
                    }
                  }
                }
                """.trimIndent(),
            )

            val candidate = assertIs<HookCodexImportCandidate.Supported>(
                CodexCliStorage(root).readHookImportCandidates(
                    sourceKind = HookCodexSourceKind.User,
                    environment = mapOf("HOOK_TOKEN" to "private-environment-value"),
                ).single(),
            )

            assertEquals("Imported checks", candidate.displayName)
            assertEquals(
                SystemCoroutineFileSystem.resolve(Path(root, "hooks.json")).toString(),
                candidate.identity.normalizedPath,
            )
            val group = candidate.template.hooks.preToolUse.single()
            assertIs<HookMatcher.Exact>(group.matcher)
            val command = group.hooks.single()
            assertEquals(
                if (CodexCliStoragePlatform.isWindows) {
                    "echo windows"
                } else {
                    "echo private-environment-value"
                },
                command.command,
            )
            assertEquals(1L, command.timeoutSeconds)
            assertFalse(command.enabled)
            assertEquals("Checking", command.statusMessage)
            assertEquals(1200, command.additionalContextLimit)
            assertEquals(
                "private-environment-value",
                candidate.template.environment.getValue("HOOK_TOKEN").value,
            )
            assertTrue(candidate.excludedDetails.any { "private_source_field" in it })
            assertTrue(candidate.excludedDetails.any { "private_group_field" in it })
            assertTrue(candidate.excludedDetails.any { "private_handler_field" in it })
            assertTrue(candidate.excludedDetails.any { "prompt handlers" in it })
            assertTrue(candidate.excludedDetails.any { "asynchronous" in it })
            assertTrue(candidate.excludedDetails.any { "SessionStart" in it })
            assertTrue(candidate.excludedDetails.any { "state entries" in it })
            assertTrue(candidate.excludedDetails.any { "trust hashes" in it })
            val details = candidate.excludedDetails.toString()
            assertFalse("must-not-appear" in details)
            assertFalse("private prompt" in details)
            assertFalse("private async command" in details)
            assertFalse("private-environment-value" in details)
            assertFalse("private-trust-hash" in details)
            assertFalse("private-state-hash" in details)
        } finally {
            deleteRecursively(root)
        }
    }

    test("explicit Hook import treats JSON and Hook-bearing TOML as separate source units") {
        val root = Path(SystemTemporaryDirectory, "codex-hook-import-units-${Random.nextLong()}")
        try {
            SystemCoroutineFileSystem.createDirectories(root)
            SystemCoroutineFileSystem.writeString(
                Path(root, "hooks.json"),
                "{not valid JSON",
            )
            SystemCoroutineFileSystem.writeString(
                Path(root, "config.toml"),
                """
                model = "ignored-model"

                [[hooks.Stop]]
                private_group_field = "must-not-appear"

                [[hooks.Stop.hooks]]
                type = "command"
                command = "finish"
                private_handler_field = "must-not-appear"
                """.trimIndent(),
            )

            val candidates = CodexCliStorage(root).readHookImportCandidates(
                sourceKind = HookCodexSourceKind.Project,
            )

            assertEquals(2, candidates.size)
            assertIs<HookCodexImportCandidate.Unsupported>(candidates.first())
            val toml = assertIs<HookCodexImportCandidate.Supported>(candidates.last())
            assertEquals("finish", toml.template.hooks.stop.single().hooks.single().command)
            assertTrue(toml.excludedDetails.any { "private_group_field" in it })
            assertTrue(toml.excludedDetails.any { "private_handler_field" in it })
            assertFalse("must-not-appear" in toml.excludedDetails.toString())
        } finally {
            deleteRecursively(root)
        }
    }

    test("explicit Hook import ignores config TOML without Hooks and disables unsupported sources") {
        val root = Path(SystemTemporaryDirectory, "codex-hook-import-unsupported-${Random.nextLong()}")
        try {
            SystemCoroutineFileSystem.createDirectories(root)
            SystemCoroutineFileSystem.writeString(
                Path(root, "hooks.json"),
                """
                {
                  "description":"Prompt only",
                  "hooks":{
                    "Stop":[{
                      "hooks":[{"type":"prompt","prompt":"private prompt"}]
                    }]
                  }
                }
                """.trimIndent(),
            )
            SystemCoroutineFileSystem.writeString(
                Path(root, "config.toml"),
                "model = \"ignored-model\"",
            )

            val candidates = CodexCliStorage(root).readHookImportCandidates(
                sourceKind = HookCodexSourceKind.User,
            )

            val unsupported = assertIs<HookCodexImportCandidate.Unsupported>(candidates.single())
            assertEquals("Prompt only", unsupported.displayName)
            assertTrue("prompt" in unsupported.detail.lowercase())
            assertFalse("private prompt" in unsupported.detail)
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
