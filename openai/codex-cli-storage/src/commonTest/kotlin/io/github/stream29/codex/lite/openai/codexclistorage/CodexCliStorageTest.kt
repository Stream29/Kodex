package io.github.stream29.codex.lite.openai.codexclistorage

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.openai.ReasoningEffort
import io.github.stream29.codex.lite.utils.osenvironment.environmentVariable
import io.github.stream29.codex.lite.utils.osenvironment.userHomeDirectory
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
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

    test("reads codex metadata files") {
        val storage = CodexCliStorage(testCodexDirectory())

        assertTrue(storage.readModelsCacheOrNull()?.models?.isNotEmpty() == true, "Expected Codex CLI models cache.")
        storage.readConfigTomlOrNull()
    }

    test("decodes typed config while ignoring unsupported native settings") {
        val root = Path(SystemTemporaryDirectory, "codex-config-${Random.nextLong()}")
        try {
            SystemCoroutineFileSystem.createDirectories(root)
            SystemCoroutineFileSystem.writeString(
                Path(root, "config.toml"),
                """
                model = "gpt-5.6"
                model_reasoning_effort = "high"
                service_tier = "fast"

                [tui.keymap.composer]
                submit = "enter"

                [tui.keymap.editor]
                insert_newline = "shift-enter"

                [mcp_servers.docs]
                url = "https://docs.example.test/mcp"
                enabled = false

                [mcp_servers.docs.http_headers]
                Authorization = "Bearer test"

                [projects."/workspace"]
                trust_level = "trusted"
                """.trimIndent(),
            )

            val config = CodexCliStorage(root).readConfigTomlOrNull()
                ?: error("Expected config.")
            assertEquals("gpt-5.6", config.model)
            assertEquals(ReasoningEffort.High, config.reasoningEffort)
            assertEquals("fast", config.serviceTier)
            assertEquals("enter", config.tui?.keymap?.composer?.submit)
            assertEquals("shift-enter", config.tui?.keymap?.editor?.insertNewline)
            assertEquals("https://docs.example.test/mcp", config.mcpServers.getValue("docs").url)
            assertEquals("Bearer test", config.mcpServers.getValue("docs").headers["Authorization"])
            assertEquals(false, config.mcpServers.getValue("docs").enabled)
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
            assertEquals(null, storage.readModelsCacheOrNull())
            assertEquals(null, storage.readConfigTomlOrNull())
        } finally {
            deleteRecursively(root)
        }
    }

    test("defaults nested keymaps") {
        val root = Path(SystemTemporaryDirectory, "codex-keymap-${Random.nextLong()}")
        try {
            SystemCoroutineFileSystem.createDirectories(root)
            SystemCoroutineFileSystem.writeString(
                Path(root, "config.toml"),
                """
                [tui]
                animations = false
                """.trimIndent(),
            )

            val config = CodexCliStorage(root).readConfigTomlOrNull()
                ?: error("Expected config.")
            val tui = assertNotNull(config.tui)
            assertEquals(null, tui.keymap.global.submit)
            assertEquals(null, tui.keymap.editor.insertNewline)
        } finally {
            deleteRecursively(root)
        }
    }

    test("rejects a cache without Codex required metadata") {
        val root = Path(SystemTemporaryDirectory, "codex-cache-${Random.nextLong()}")
        try {
            SystemCoroutineFileSystem.createDirectories(root)
            SystemCoroutineFileSystem.writeString(
                Path(root, "models_cache.json"),
                "{\"fetched_at\":\"2026-07-23T00:00:00Z\"}",
            )

            assertFails {
                CodexCliStorage(root).readModelsCacheOrNull()
            }
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
