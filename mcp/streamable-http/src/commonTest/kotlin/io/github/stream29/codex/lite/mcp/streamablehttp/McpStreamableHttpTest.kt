package io.github.stream29.codex.lite.mcp.streamablehttp

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlin.test.assertTrue

val mcpStreamableHttpTest by testSuite {
    test("cancelling the owner cancels the HTTP client") {
        val ownerJob = Job()
        val client = CoroutineScope(ownerJob).McpStreamableHttpClient()
        val clientJob = requireNotNull(client.coroutineContext[Job])

        ownerJob.cancelAndJoin()
        clientJob.join()

        assertTrue(clientJob.isCancelled)
    }
}
