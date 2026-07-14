package io.github.stream29.codex.lite.openai.codexclistorage

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.utils.osenvironment.environmentVariable
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue



private fun testCodexDirectory(): Path =
    environmentVariable("CODEX_HOME")
        ?.takeIf(String::isNotBlank)
        ?.let(::Path)
        ?: defaultCodexDirectory()
        ?: error("CODEX_HOME or a readable user home directory must be set for real Codex CLI storage tests.")

val codexCliStorageTest by testSuite {
    test("detects codex directory") {
        val codexDirectory = testCodexDirectory()

        assertEquals(codexDirectory, detectCodexDirectory(listOf(Path("/tmp/not-a-codex-dir"), codexDirectory)))
        assertTrue(CodexCliStorage(codexDirectory).isCodexDirectory(), "Expected $codexDirectory to contain Codex CLI auth.")
    }

    test("reads codex auth") {
        val auth = CodexCliStorage(testCodexDirectory()).readAuth()

        assertTrue(auth.accessToken.isNotBlank(), "Expected Codex CLI access token.")
        assertNotNull(auth.accountId, "Expected Codex CLI ChatGPT account id.")
    }

    test("reads codex metadata files") {
        val storage = CodexCliStorage(testCodexDirectory())

        assertTrue(storage.readModelsCache().models.isNotEmpty(), "Expected Codex CLI models cache.")
        assertTrue(storage.readConfigToml().isNotBlank(), "Expected Codex CLI config TOML.")
    }
}
