package io.github.stream29.kodex.cli.rpc

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.contract.TokenCountDiagnostics
import io.github.stream29.kodex.agentstorage.contract.TokenCountKind
import io.github.stream29.kodex.agentstorage.contract.TokenCountSnapshot
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertNull

val ultrafastTierWarningsTest by testSuite {
    test("opening history and repeated records do not warn") {
        val cursor = UltrafastTierWarnings(7, 12, "old")
        assertNull(cursor.observe(12, 7, tierSnapshot("old")))
        assertNull(cursor.observe(8, 7, tierSnapshot("older")))
        assertNotNull(cursor.observe(13, 7, tierSnapshot("new")))
        assertNull(cursor.observe(13, 7, tierSnapshot("new")))
        assertNull(cursor.observe(14, 7, tierSnapshot("new")))
    }
    test("nonce changes establish a historical baseline and allow new forward records") {
        val cursor = UltrafastTierWarnings(7, 12, "old")
        assertNotNull(cursor.observe(13, 7, tierSnapshot("new")))
        assertNull(cursor.observe(5, 8, tierSnapshot("restored")))
        assertNull(cursor.observe(5, 8, tierSnapshot("restored")))
        assertNotNull(cursor.observe(6, 8, tierSnapshot("after-revert")))
        assertNull(cursor.observe(6, 9, tierSnapshot("after-rebind")))
    }
    test("only observed response diagnostics with a different actual tier warn") {
        val cursor = UltrafastTierWarnings(7, 0, null)
        assertNull(cursor.observe(1, 7, null))
        assertNull(cursor.observe(2, 7, TokenCountSnapshot(TokenCountKind.Compaction, 0)))
        assertNull(cursor.observe(3, 7, TokenCountSnapshot(TokenCountKind.Response, 23)))
        assertNull(cursor.observe(4, 7, tierSnapshot("missing", actual = null)))
        assertNull(cursor.observe(5, 7, tierSnapshot("same", actual = "ultrafast")))
        assertNull(cursor.observe(6, 7, tierSnapshot("fast", requested = "priority")))
        assertNull(cursor.observe(7, 7, tierSnapshot("empty", actual = "")))
        val warning = assertNotNull(cursor.observe(8, 7, tierSnapshot("different", actual = "future-tier")))
        assertContains(warning, "Requested ultrafast")
        assertContains(warning, "'future-tier'")
        assertContains(warning, "Response: different")
        assertContains(warning, "saved selection was not changed")
    }
}

private fun tierSnapshot(
    id: String,
    actual: String? = "default",
    requested: String = "ultrafast",
): TokenCountSnapshot = TokenCountSnapshot(
    TokenCountKind.Response, 23,
    diagnostics = TokenCountDiagnostics(
        requestedModel = "captured-model", requestedServiceTier = requested,
        responseId = id, serviceTier = actual,
    ),
)
