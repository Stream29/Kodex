@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.stream29.kodex.rpc.browser

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.backend.BackendBusinessProtocol
import io.github.stream29.kodex.backend.LocalBrowserEndpoint
import io.ktor.client.engine.js.JsError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlin.js.JsAny
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal val fetchOwner = Mutex()
private val fetchEndpoint = LocalBrowserEndpoint("fetch-test", 1, "a".repeat(64), BackendBusinessProtocol)
private suspend fun realBrowserIo(block: suspend () -> Unit) =
    withContext(Dispatchers.Default.limitedParallelism(1)) { fetchOwner.withLock { block() } }

internal val browserFetchFailureTest by testSuite {
    test("non-Exception retry classification recognizes Ktor JS errors but not arbitrary fatal errors") {
        val failure = JsError(browserFetchTypeError())
        assertTrue(isBrowserNetworkFailure(failure))
        assertTrue(isBrowserNetworkFailure(Error("Fail to fetch", failure)))
        assertFalse(isBrowserNetworkFailure(Error("programmer fault")))
        assertFalse(isBrowserNetworkFailure(AssertionError("programmer assertion")))
    }
    test("actual Ktor rejected fetch becomes a contained connection failure rather than a fatal Error") {
        realBrowserIo {
            val replacement = interceptHeartbeatFetch(pending = false)
            var enteredBusiness = false
            try {
                val failure = assertFailsWith<IllegalStateException> {
                    withTimeout(5_000) {
                        withLocalBrowserClient<Unit>(fetchEndpoint, null) { _, _ -> enteredBusiness = true }
                    }
                }
                assertFalse(failure is CancellationException)
                assertContains(failure.message.orEmpty(), "Unable to connect")
                assertFalse(enteredBusiness)
            } finally {
                val calls = heartbeatFetchCalls(replacement)
                restoreHeartbeatFetch(replacement)
                assertEquals(1, calls, "Only this owned heartbeat is intercepted; no external port is contacted.")
            }
        }
    }
    test("caller cancellation during a pending browser fetch remains cancellation and releases the owner") {
        realBrowserIo {
            val replacement = interceptHeartbeatFetch(pending = true)
            var cancelled = false
            var enteredBusiness = false
            try {
                coroutineScope {
                    val owner = launch {
                        try {
                            withLocalBrowserClient<Unit>(fetchEndpoint, null) { _, _ -> enteredBusiness = true }
                        } catch (failure: CancellationException) { cancelled = true; throw failure }
                    }
                    withTimeout(5_000) { while (heartbeatFetchCalls(replacement) == 0) delay(1) }
                    withTimeout(5_000) { owner.cancelAndJoin() }
                    assertTrue(owner.isCancelled)
                }
                assertTrue(cancelled)
                assertFalse(enteredBusiness)
            } finally { restoreHeartbeatFetch(replacement) }
        }
    }
}

@JsFun("""(pending) => {
    const original = globalThis.fetch;
    const state = {calls: 0};
    const replacement = function(input, init) {
        const url = typeof input === 'string' ? input : input.url;
        if (url !== 'http://127.0.0.1:1/heartbeat') return original.call(globalThis, input, init);
        state.calls++;
        if (!pending) return Promise.reject(new TypeError('Failed to fetch'));
        return new Promise((resolve, reject) => {
            init.signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), {once: true});
        });
    };
    state.restore = () => {
        if (globalThis.fetch !== replacement) throw new Error('Fetch test ownership changed');
        globalThis.fetch = original;
    };
    globalThis.fetch = replacement;
    return state;
}""")
private external fun interceptHeartbeatFetch(pending: Boolean): JsAny

@JsFun("(state) => state.calls")
private external fun heartbeatFetchCalls(state: JsAny): Int

@JsFun("(state) => state.restore()")
private external fun restoreHeartbeatFetch(state: JsAny)

@JsFun("() => new TypeError('Failed to fetch')")
private external fun browserFetchTypeError(): JsAny
