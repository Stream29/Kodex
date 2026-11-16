package io.github.stream29.kodex.mcp.impl

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.mcp.contract.McpClient
import io.github.stream29.kodex.mcp.contract.McpClientState
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Confirmed central regressions, now enabled on every JVM run.
 * The real SDK owner/admission primitive is gated; full service observer
 * scheduling is covered separately by the retained IO service suite.
 */
val mcpOwnerPublicationGateTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("B2-R1 stale connection catalog must not replace a successfully reconnected SDK catalog") {
        withTimeout(10.seconds) {
            val scope = CoroutineScope(currentCoroutineContext()).supervisorChildScope()
            var nextTools = listOf("old")
            var published: McpClient? = null
            var staleCatalog: McpClientCatalog? = null
            val owner = McpClientOwner(
                scope,
                "server",
                McpServerConfiguration.Stdio("offline"),
                openTransport = { OfflineSdkTransport(nextTools) },
                publishCatalog = { currentOwner, catalog ->
                    if (staleCatalog == null) staleCatalog = catalog
                    currentOwner.publishIfCurrent(catalog) { published = currentOwner.client(catalog) }
                },
            )
            try {
                owner.reconnect()
                assertEquals("old", published!!.routeName())
                val capturedStale = assertNotNull(staleCatalog)

                nextTools = listOf("new")
                owner.reconnect()
                assertEquals("new", published.routeName())

                val accepted = owner.publishIfCurrent(capturedStale) {
                    published = owner.client(capturedStale)
                }
                assertFalse(accepted)
                assertEquals("new", published.routeName(), "stale catalog must not replace reconnect generation")
            } finally {
                withContext(NonCancellable) {
                    scope.cancelAndJoin()
                    owner.close()
                }
            }
        }
    }

    test("B2-R2 auth-blocked retained SDK tools must be unavailable before retirement acquires its writer") {
        withTimeout(10.seconds) {
            val scope = CoroutineScope(currentCoroutineContext()).supervisorChildScope()
            val entered = CompletableDeferred<Unit>()
            val releaseReader = CompletableDeferred<Unit>()
            val releaseRetirement = CompletableDeferred<Unit>()
            val transport = OfflineSdkTransport(listOf("echo"))
            var published: McpClient? = null
            val owner = McpClientOwner(
                scope,
                "server",
                McpServerConfiguration.Stdio("offline"),
                openTransport = { transport },
                publishCatalog = { currentOwner, catalog -> published = currentOwner.client(catalog); true },
            )
            try {
                owner.reconnect()
                val reading = scope.async {
                    owner.call { entered.complete(Unit); releaseReader.await() }
                }
                entered.await()
                // The exact blocked view construction used by reconciliation.
                // Hold the publication-to-retirement boundary, not a queued
                // writer: ReadWriteMutex excludes later readers once queued.
                val blocked = McpAuthenticationBlockedClient("server", published!!.listTools())
                val retiring = scope.async(start = CoroutineStart.UNDISPATCHED) {
                    releaseRetirement.await()
                    owner.close()
                }
                assertFalse(retiring.isCompleted)
                assertEquals(McpClientState.AuthenticationBlocked, blocked.state.value)
                val result = blocked.listTools().single().call(JsonObject(emptyMap()))
                assertTrue(result.isError == true, "blocked retained tool must not call its still-healthy SDK owner")
                assertTrue(transport.calledNames.isEmpty())
                releaseRetirement.complete(Unit)
                releaseReader.complete(Unit)
                reading.await()
                retiring.await()
            } finally {
                releaseRetirement.complete(Unit)
                releaseReader.complete(Unit)
                withContext(NonCancellable) {
                    scope.cancelAndJoin()
                    owner.close()
                }
            }
        }
    }
}

private fun McpClient.routeName(): String =
    listTools().single().toolName
