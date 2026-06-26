package io.github.stream29.codex.lite.openai.codexclistorage

import io.github.stream29.codex.lite.utils.hosttest.environmentVariable
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CodexCliStorageTest {
    @Test
    fun detectsCodexDirectory() {
        val codexDirectory = testCodexDirectory()

        assertEquals(codexDirectory, detectCodexDirectory(listOf(Path("/tmp/not-a-codex-dir"), codexDirectory)))
        assertTrue(isCodexDirectory(codexDirectory), "Expected $codexDirectory to contain Codex CLI auth.")
    }

    @Test
    fun readsCodexAuth() {
        val auth = readCodexAuth(testCodexDirectory())

        assertTrue(auth.accessToken.isNotBlank(), "Expected Codex CLI access token.")
        assertNotNull(auth.accountId, "Expected Codex CLI ChatGPT account id.")
    }
}

private fun testCodexDirectory(): Path {
    val explicitCodexHome = environmentVariable("CODEX_HOME")?.takeIf(String::isNotBlank)
    if (explicitCodexHome != null) {
        return Path(explicitCodexHome)
    }

    val userHome = environmentVariable("HOME")?.takeIf(String::isNotBlank)
        ?: environmentVariable("USERPROFILE")?.takeIf(String::isNotBlank)
        ?: error("CODEX_HOME, HOME, or USERPROFILE must be set for real Codex CLI storage tests.")
    return Path(userHome, ".codex")
}
