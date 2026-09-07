package io.github.stream29.kodex.openai.codexclistorage

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.osenvironment.environmentVariable
import io.github.stream29.kodex.utils.osenvironment.userHomeDirectory
import kotlinx.io.files.Path
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun testCodexDirectory(): Path =
    environmentVariable("CODEX_HOME")
        ?.takeIf(String::isNotBlank)
        ?.let(::Path)
        ?: userHomeDirectory()?.let { home -> Path(home, ".codex") }
        ?: error("CODEX_HOME or a readable user home directory must be set for real Codex CLI storage tests.")

val codexCliAuthIntegrationTest by testSuite {
    test("reads codex auth") {
        val auth = CodexCliStorage(testCodexDirectory()).readAuthOrNull()
            ?: error("Expected Codex CLI auth.")

        val tokens = assertNotNull(auth.tokens, "Expected Codex CLI auth tokens.")
        assertTrue(tokens.accessToken.isNotBlank(), "Expected Codex CLI access token.")
        assertNotNull(tokens.accountId, "Expected Codex CLI ChatGPT account id.")
    }
}
