package io.github.stream29.kodex.mcp.streamablehttp

import de.infix.testBalloon.framework.core.testSuite
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlin.test.assertEquals
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

    test("authorization retries one 401 with a forced refresh") {
        val requests = mutableListOf<String?>()
        val forceRefreshValues = mutableListOf<Boolean>()
        val base = HttpClient(
            MockEngine { request ->
                val authorization = request.headers[HttpHeaders.Authorization]
                requests += authorization
                respond(
                    content = "",
                    status = if (authorization == "Bearer fresh-token") {
                        HttpStatusCode.OK
                    } else {
                        HttpStatusCode.Unauthorized
                    },
                    headers = headersOf(),
                )
            },
        )
        val client = base.withMcpAuthorization { forceRefresh ->
            forceRefreshValues += forceRefresh
            if (forceRefresh) "fresh-token" else "expired-token"
        }

        val response = client.get("https://server.example.test/mcp")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf(false, true), forceRefreshValues)
        assertEquals(
            listOf<String?>("Bearer expired-token", "Bearer fresh-token"),
            requests,
        )
        client.close()
        base.close()
    }

    test("derived clients isolate authorization for the same endpoint") {
        val requests = mutableListOf<String?>()
        val base = HttpClient(
            MockEngine { request ->
                requests += request.headers[HttpHeaders.Authorization]
                respond("", HttpStatusCode.OK, headersOf())
            },
        )
        val alpha = base.withMcpAuthorization { "alpha-token" }
        val beta = base.withMcpAuthorization { "beta-token" }

        alpha.get("https://shared.example.test/mcp")
        beta.get("https://shared.example.test/mcp")

        assertEquals(
            listOf<String?>("Bearer alpha-token", "Bearer beta-token"),
            requests,
        )
        alpha.close()
        beta.close()
        base.close()
    }
}
