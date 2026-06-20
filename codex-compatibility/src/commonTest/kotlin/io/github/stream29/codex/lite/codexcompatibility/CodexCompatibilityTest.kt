package io.github.stream29.codex.lite.codexcompatibility

import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CodexCompatibilityTest {
    @Test
    fun detectsCodexDirectory() {
        val codexDirectory = Path("/home/stream/.codex")

        assertEquals(codexDirectory, detectCodexDirectory(listOf(Path("/tmp/not-a-codex-dir"), codexDirectory)))
        assertTrue(isCodexDirectory(codexDirectory), "Expected /home/stream/.codex to contain Codex CLI auth.")
    }

    @Test
    fun readsCodexAuth() {
        val auth = readCodexAuth(Path("/home/stream/.codex"))

        assertTrue(auth.accessToken.isNotBlank(), "Expected Codex CLI access token.")
        assertNotNull(auth.accountId, "Expected Codex CLI ChatGPT account id.")
    }
}
