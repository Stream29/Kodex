@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, io.ktor.utils.io.InternalAPI::class)

package io.github.stream29.kodex.utils.ktorclientext.kodexcurl

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.url
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.io.IOException
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

val kodexCurlTerminalCleanupTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("completed getInfo failure settles pending promise and suppresses every cleanup fault") {
        withNativeHttpFixture("ok".encodeToByteArray()) { fixture ->
            val primary = IOException("completed getInfo")
            val cleanup = releaseFaults().filterKeys { it != "forbidReuse" }
            val hooks = KodexCurlNativeTestHooks().apply {
                publishHeaders = false
                afterOperation = { operation ->
                    if (operation == "completedGetInfo") throw primary
                    cleanup[operation]?.let { throw it }
                }
            }
            withActualHandler(fixture, hooks) { api, response, _, _ ->
                val observed = assertFailsWith<IOException> { withTimeout(5.seconds) { response.await() } }
                assertSame(primary, observed)
                assertSuppressed(primary, cleanup.values)
                assertFalse(api.hasHandlers(), "Promise settled but holder stayed in actual map")
            }
        }
    }

    test("post-header transport failure remains body primary through remove and dispose faults") {
        withNativeHttpFixture("partial".encodeToByteArray(), 100) { fixture ->
            val cleanup = releaseFaults().filterKeys { it != "forbidReuse" }
            var released = false
            val hooks = KodexCurlNativeTestHooks().apply {
                afterOperation = { operation ->
                    if (operation == "responseWrapper") released = true
                    cleanup[operation]?.let { throw it }
                }
            }
            withActualHandler(fixture, hooks) { api, response, _, _ ->
                val actual = withTimeout(5.seconds) { response.await() }
                val body = assertIs<KodexCurlHttpResponseBody>(actual.responseBody)
                withTimeout(5.seconds) { fixture.sent.await(); body.bodyChannel.awaitContent() }
                fixture.closePeerAfterHeaders()
                withTimeout(5.seconds) { while (!body.bodyChannel.isClosedForWrite) delay(1) }
                val primary = checkNotNull(body.bodyChannel.closedCause)
                assertTrue(failureGraph(primary).any {
                    it is IOException && it.message?.contains("Connection failed") == true
                }, "Cleanup replaced the actual CURLE_PARTIAL_FILE failure: $primary")
                assertSuppressed(primary, cleanup.values)
                assertTrue(released)
                assertFalse(api.hasHandlers())
                assertTrue(response.isCompleted && !response.isCancelled, "Headers were already delivered")
            }
        }
    }

    test("successful transfer exposes cleanup failure instead of successful EOF") {
        withNativeHttpFixture("ok".encodeToByteArray()) { fixture ->
            val cleanup = IOException("remove after actual successful transfer")
            val hooks = KodexCurlNativeTestHooks().apply {
                publishHeaders = false
                afterOperation = { if (it == "multiRemove") throw cleanup }
            }
            withActualHandler(fixture, hooks) { api, response, loopFailure, _ ->
                assertSame(cleanup, assertFailsWith<IOException> { withTimeout(5.seconds) { response.await() } })
                assertSame(cleanup, withTimeout(5.seconds) { loopFailure.await() })
                assertFalse(api.hasHandlers())
            }
        }
        // The same cleanup failure must also reach a body whose headers have
        // already been published, rather than only a still-pending promise.
        withNativeHttpFixture("ok".encodeToByteArray()) { fixture ->
            val cleanup = IOException("remove after headers")
            val hooks = KodexCurlNativeTestHooks().apply {
                afterOperation = { if (it == "multiRemove") throw cleanup }
            }
            withActualHandler(fixture, hooks) { api, response, loopFailure, _ ->
                val body = assertIs<KodexCurlHttpResponseBody>(withTimeout(5.seconds) { response.await() }.responseBody)
                withTimeout(5.seconds) { while (!body.bodyChannel.isClosedForWrite) delay(1) }
                assertTrue(failureGraph(checkNotNull(body.bodyChannel.closedCause)).any { it === cleanup })
                assertSame(cleanup, withTimeout(5.seconds) { loopFailure.await() })
                assertFalse(api.hasHandlers())
            }
        }
    }

    test("admitted cancellation settles promise and keeps caller cause through cleanup faults") {
        withNativeHttpFixture("partial".encodeToByteArray(), 100) { fixture ->
            val primary = CancellationException("caller cancellation")
            val cleanup = releaseFaults()
            val hooks = KodexCurlNativeTestHooks().apply {
                publishHeaders = false
                afterOperation = { cleanup[it]?.let { failure -> throw failure } }
            }
            withActualHandler(fixture, hooks) { api, response, _, handle ->
                withTimeout(5.seconds) { fixture.sent.await() }
                assertFalse(response.isCompleted)
                api.cancelRequest(handle, primary)
                val observed = assertFailsWith<CancellationException> { withTimeout(5.seconds) { response.await() } }
                assertSame(primary, observed)
                assertSuppressed(primary, cleanup.values)
                assertFalse(api.hasHandlers())
            }
        }
    }

    test("engine close settles admitted promise and exposes release failure without replacing cancellation") {
        withNativeHttpFixture("partial".encodeToByteArray(), 100) { fixture ->
            val cleanup = releaseFaults()
            val hooks = KodexCurlNativeTestHooks().apply {
                publishHeaders = false
                afterOperation = { cleanup[it]?.let { failure -> throw failure } }
            }
            withActualHandler(fixture, hooks, drive = false) { api, response, _, _ ->
                assertSame(cleanup["forbidReuse"], assertFailsWith<IOException> { api.close() })
                val primary = assertFailsWith<CancellationException> { withTimeout(5.seconds) { response.await() } }
                assertEquals("Kodex Curl client engine closed", primary.message)
                assertSuppressed(primary, cleanup.values)
                assertFalse(api.hasHandlers())
            }
        }
    }

    for (kind in listOf("getInfo primary", "admitted cancellation")) {
        test("$kind has one acyclic cleanup graph with shared faults and later exact primary") {
            withNativeHttpFixture("partial".encodeToByteArray(), 100) { fixture ->
                val primary = if (kind == "getInfo primary") IOException("original getInfo")
                    else CancellationException("original caller cancellation")
                val shared = IOException("shared native and holder cleanup")
                val later = IOException("later independent cleanup")
                val backEdge = IOException("cleanup whose cause contains original primary", primary)
                val released = mutableListOf<String>()
                val hooks = KodexCurlNativeTestHooks().apply {
                    publishHeaders = false
                    afterOperation = { operation ->
                        released += operation // Hooks run after the actual release.
                        when (operation) {
                            "completedGetInfo" -> if (kind == "getInfo primary") throw primary
                            "multiRemove", "responseDataRef", "requestWrapper", "holderDispose" -> throw shared
                            "easyCleanup", "responseWrapper" -> throw primary
                            "responseHeaders" -> throw later
                            "cancellationHandler", "requestHeaders" -> throw backEdge
                        }
                    }
                }
                withActualHandler(fixture, hooks) { api, response, _, handle ->
                    withTimeout(5.seconds) { fixture.sent.await() }
                    if (kind == "getInfo primary") fixture.closePeerAfterHeaders()
                    else api.cancelRequest(handle, primary)
                    val observed = assertFailsWith<Throwable> { withTimeout(5.seconds) { response.await() } }
                    assertSame(primary, observed)
                    assertCleanupGraph(primary, listOf(shared, later))
                    val context = failureGraph(primary).single {
                        it.message?.contains("cleanup whose cause contains original primary") == true
                    }
                    assertNull(context.cause, "Unsafe original cleanup must not create a back edge")
                    assertFalse(failureGraph(primary).any { it === backEdge })
                    assertSame(primary, backEdge.cause, "Do not rewrite a supplied cleanup's cause")
                    assertTrue(released.containsAll(listOf(
                        "multiRemove", "easyCleanup", "requestHeaders", "responseDataRef",
                        "requestWrapper", "responseWrapper", "bodyClose",
                    )), "Real terminal releases did not all precede fault observation: $released")
                    assertFalse(api.hasHandlers())
                }
            }
        }
    }

    test("successful transfer keeps first cleanup identity with reused release faults") {
        withNativeHttpFixture("ok".encodeToByteArray()) { fixture ->
            val first = IOException("successful transfer remove failure")
            val later = IOException("successful transfer later failure")
            val backEdge = IOException("cleanup caused by first cleanup", first)
            val hooks = KodexCurlNativeTestHooks().apply {
                publishHeaders = false
                afterOperation = {
                    when (it) {
                        "multiRemove", "easyCleanup", "requestWrapper", "responseWrapper" -> throw first
                        "responseHeaders", "requestHeaders" -> throw later
                        "responseDataRef", "holderDispose" -> throw backEdge
                    }
                }
            }
            withActualHandler(fixture, hooks) { api, response, loopFailure, _ ->
                assertSame(first, assertFailsWith<IOException> { withTimeout(5.seconds) { response.await() } })
                assertSame(first, withTimeout(5.seconds) { loopFailure.await() })
                assertCleanupGraph(first, listOf(later))
                assertEquals(1, first.suppressedExceptions.count {
                    it.message?.contains("cleanup caused by first cleanup") == true
                })
                assertFalse(api.hasHandlers())
            }
        }
    }

    test("API close exposes one cleanup graph across holders and multi release") {
        withNativeHttpFixture("partial".encodeToByteArray(), 100) { fixture ->
            val shared = IOException("shared close cleanup")
            val later = IOException("later close cleanup")
            val overlapping = IOException("shared holder and multi cleanup whose cause reaches first cleanup", shared)
            val hooks = KodexCurlNativeTestHooks().apply {
                publishHeaders = false
                afterOperation = {
                    when (it) {
                        "multiRemove", "requestHeaders", "responseWrapper" -> throw shared
                        "easyCleanup", "responseDataRef" -> throw later
                        "requestWrapper", "multiCleanup" -> throw overlapping
                    }
                }
            }
            withActualHandler(fixture, hooks, drive = false) { api, response, _, _ ->
                val secondCall = Job()
                val secondResponse = CompletableDeferred<KodexCurlSuccess>()
                try {
                    val request = HttpRequestBuilder().apply { url(fixture.url) }.build()
                        .toKodexCurlRequest(KodexCurlEngineConfig(), secondCall)
                    api.scheduleRequest(request, secondResponse) { actual, cause -> api.cancelRequest(actual, cause) }
                    assertSame(shared, assertFailsWith<IOException> { api.close() })
                    val primary = assertFailsWith<CancellationException> { response.await() }
                    assertSame(primary, assertFailsWith<CancellationException> { secondResponse.await() })
                    assertCleanupGraph(primary, listOf(shared, later))
                    assertCleanupGraph(shared, listOf(later))
                    assertSame(later, shared.suppressedExceptions.first())
                    assertEquals(1, shared.suppressedExceptions.count {
                        it.message?.contains("shared holder and multi cleanup") == true
                    }, "Same unsafe cleanup must retain one diagnostic across holders and multi release")
                    assertFalse(api.hasHandlers())
                    api.close() // Idempotent: do not touch released native resources.
                } finally {
                    secondCall.cancelAndJoin()
                }
            }
        }
    }

    test("late unpause straddling release never wakes the released multi handle") {
        withNativeHttpFixture("partial".encodeToByteArray(), 100) { fixture ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val multiReleased = CompletableDeferred<Unit>()
            val closeReachedNativeRelease = CompletableDeferred<Unit>()
            var wakes = 0
            var queue: ((KodexCurlRequestHandle) -> Unit)? = null
            val hooks = KodexCurlNativeTestHooks().apply {
                onScheduled = { _, unpause -> queue = unpause }
                beforeNativeWakeup = {
                    entered.complete(Unit)
                    runBlocking { withTimeout(5.seconds) { release.await() } }
                }
                // Intercept only the last native call. On the defective path
                // this records a forbidden post-release call without a real UAF.
                nativeWakeup = { wakes++; assertFalse(multiReleased.isCompleted) }
                afterOperation = {
                    if (it == "beforeMultiCleanup") closeReachedNativeRelease.complete(Unit)
                    if (it == "multiCleanup") multiReleased.complete(Unit)
                }
            }
            withActualHandler(fixture, hooks, drive = false) { api, _, _, handle ->
                coroutineScope {
                    val late = async(Dispatchers.IO) { checkNotNull(queue)(handle) }
                    var closing: Deferred<Unit>? = null
                    try {
                        withTimeout(5.seconds) { entered.await() }
                        val close = async(Dispatchers.IO) { api.close() }
                        closing = close
                        withTimeout(5.seconds) { closeReachedNativeRelease.await() }
                        assertNull(withTimeoutOrNull(100) { multiReleased.await() },
                            "Native multi release passed an in-flight wakeup's lifecycle lock")
                        assertFalse(close.isCompleted)
                        release.complete(Unit)
                        withTimeout(5.seconds) { late.await(); close.await() }
                        assertTrue(multiReleased.isCompleted)
                        assertEquals(1, wakes, "Wakeup did not execute before native release")
                        checkNotNull(queue)(handle) // Wholly after release: must not wake again.
                        assertEquals(1, wakes, "Closed native handler woke a released multi handle")
                    } finally {
                        release.complete(Unit)
                        withContext(NonCancellable) { late.await(); closing?.await() }
                    }
                }
            }
        }
    }

    test("stale unpause token cannot resume a forced reused easy address") {
        withNativeHttpFixture("old".encodeToByteArray()) { oldFixture ->
          withNativeHttpFixture("partial".encodeToByteArray(), 100) { fixture ->
            var unpauses = 0
            val processed = CompletableDeferred<Unit>()
            val resumed = CompletableDeferred<Unit>()
            var queue: ((KodexCurlRequestHandle) -> Unit)? = null
            val hooks = KodexCurlNativeTestHooks().apply {
                onScheduled = { _, unpause -> queue = unpause }
                afterOperation = {
                    if (it == "staleUnpauseDiscarded") processed.complete(Unit)
                    if (it == "easyUnpause") { unpauses++; processed.complete(Unit); resumed.complete(Unit) }
                }
            }
            withActualHandler(oldFixture, hooks) { api, oldResponse, _, old ->
                val oldBody = assertIs<KodexCurlHttpResponseBody>(withTimeout(5.seconds) { oldResponse.await() }.responseBody)
                withTimeout(5.seconds) { while (!oldBody.bodyChannel.isClosedForWrite) delay(1) }
                assertFalse(api.hasHandlers(), "Old request still owns its native handle")
                val currentCall = Job()
                var current: KodexCurlRequestHandle? = null
                hooks.onScheduled = { actual, unpause -> current = actual; queue = unpause }
                try {
                    val request = HttpRequestBuilder().apply { url(fixture.url) }.build()
                        .toKodexCurlRequest(KodexCurlEngineConfig(), currentCall)
                    api.scheduleRequest(request, CompletableDeferred()) { actual, cause -> api.cancelRequest(actual, cause) }
                    val actual = checkNotNull(current)
                    assertTrue(old.token !== actual.token)
                    // Force the address of a genuinely retired token to be the
                    // newly admitted actual easy address. No invented token or
                    // fake pointer; malloc address reuse itself is not claimed.
                    checkNotNull(queue)(KodexCurlRequestHandle(actual.easyHandle, old.token))
                    withTimeout(5.seconds) { processed.await() }
                    assertEquals(0, unpauses, "Stale callback resumed a different admitted request")
                    checkNotNull(queue)(actual)
                    withTimeout(5.seconds) { resumed.await() }
                    assertEquals(1, unpauses)
                } finally {
                    currentCall.cancelAndJoin()
                }
            }
          }
        }
    }
}

private fun releaseFaults(): Map<String, IOException> = listOf(
    "forbidReuse", "multiRemove", "easyCleanup", "responseHeaders",
    "cancellationHandler", "requestHeaders", "responseDataRef",
    "requestWrapper", "responseWrapper", "beforeBodyClose",
).associateWith { IOException("release fault: $it") }

private fun assertSuppressed(primary: Throwable, failures: Collection<Throwable>) {
    val graph = failureGraph(primary)
    for (failure in failures) {
        assertTrue(graph.any { it === failure }, "Lost secondary $failure under $primary")
    }
}

private fun failureGraph(root: Throwable): List<Throwable> {
    val visited = mutableListOf<Throwable>()
    val path = mutableListOf<Throwable>()
    val pending = mutableListOf(root to false)
    while (pending.isNotEmpty()) {
        val (current, leaving) = pending.removeAt(pending.lastIndex)
        if (leaving) {
            assertSame(current, path.removeAt(path.lastIndex))
            continue
        }
        assertFalse(path.any { it === current }, "Cycle in cause/suppressed graph at $current")
        if (visited.any { it === current }) continue
        visited += current
        path += current
        pending += current to true
        current.cause?.let { pending += it to false }
        current.suppressedExceptions.forEach { pending += it to false }
    }
    return visited
}

private fun assertCleanupGraph(primary: Throwable, failures: Collection<Throwable>) {
    val graph = failureGraph(primary)
    val references = listOf(primary) + graph.flatMap { node ->
        listOfNotNull(node.cause) + node.suppressedExceptions
    }
    for (node in graph) {
        assertEquals(1, references.count { it === node }, "Duplicate identity path to $node")
    }
    failures.forEach { failure ->
        assertEquals(1, references.count { it === failure }, "Lost or duplicated cleanup $failure")
    }
}

private suspend fun withActualHandler(
    fixture: NativeHttpFixture,
    hooks: KodexCurlNativeTestHooks,
    drive: Boolean = true,
    block: suspend (KodexCurlMultiApiHandler, CompletableDeferred<KodexCurlSuccess>,
                    CompletableDeferred<Throwable>, KodexCurlRequestHandle) -> Unit,
) = withContext(Dispatchers.IO.limitedParallelism(1)) {
    coroutineScope {
        val call = Job()
        val api = KodexCurlMultiApiHandler(hooks)
        val response = CompletableDeferred<KodexCurlSuccess>()
        val loopFailure = CompletableDeferred<Throwable>()
        var handle: KodexCurlRequestHandle? = null
        val scheduled = hooks.onScheduled
        hooks.onScheduled = { actual, queue -> handle = actual; scheduled?.invoke(actual, queue) }
        var loop: Job? = null
        try {
            val request = HttpRequestBuilder().apply { url(fixture.url) }.build()
                .toKodexCurlRequest(KodexCurlEngineConfig(), call)
            api.scheduleRequest(request, response) { actual, cause -> api.cancelRequest(actual, cause) }
            if (drive) loop = launch {
                try {
                    memScoped {
                        val running = alloc<IntVar>()
                        while (isActive) {
                            api.perform(running)
                            delay(1)
                        }
                    }
                } catch (failure: Throwable) {
                    if (failure !is CancellationException) loopFailure.complete(failure)
                }
            }
            block(api, response, loopFailure, checkNotNull(handle))
        } finally {
            withContext(NonCancellable) {
                loop?.cancelAndJoin()
                // Disable faults during test-owned emergency cleanup.
                hooks.afterOperation = null
                hooks.beforeNativeWakeup = null
                api.close()
                fixture.closePeerAfterHeaders()
                call.cancelAndJoin()
            }
        }
    }
}
