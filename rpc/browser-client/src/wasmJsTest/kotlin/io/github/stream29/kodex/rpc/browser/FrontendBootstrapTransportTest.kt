@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.stream29.kodex.rpc.browser

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.js.JsAny
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

private const val LaunchOrigin = "http://127.0.0.1:1"
private val LaunchPath = "/web/${"a".repeat(64)}/"
private val LaunchTicket = "b".repeat(64)
private val bootstrapFetchOwner = Mutex()

internal val frontendBootstrapTransportTest by testSuite {
    test("cross-Origin and missing launch tickets fail before a business connection") {
        assertFailsWith<IllegalArgumentException> {
            withLocalFrontendClient(LaunchOrigin, LaunchPath, LaunchTicket) { _, _ -> fail("Unexpected connection") }
        }
        assertFailsWith<IllegalArgumentException> { bootstrapLocalFrontend(LaunchOrigin, LaunchPath, null) }
    }
    test("bootstrap sends only the ticket and does not retry a lost reply") {
        withContext(Dispatchers.Default) { bootstrapFetchOwner.withLock {
            for (mode in listOf("valid", "lost")) {
                val state = interceptBootstrapFetch(mode)
                try {
                    if (mode == "lost") {
                        assertFailsWith<IllegalStateException> { bootstrapLocalFrontend(LaunchOrigin, LaunchPath, LaunchTicket) }
                    } else {
                        val info = bootstrapLocalFrontend(LaunchOrigin, LaunchPath, LaunchTicket)
                        assertEquals("direct-test", info.endpoint.instanceId)
                        assertEquals(1234, info.endpoint.port)
                        assertEquals("/project", info.workingDirectory)
                    }
                    assertEquals(1, bootstrapFetchCount(state))
                    assertTrue(bootstrapFetchClean(state))
                } finally { restoreBootstrapFetch(state) }
            }
        } }
    }
    test("oversized streaming bootstrap is cancelled without reading the rest") {
        withContext(Dispatchers.Default) { bootstrapFetchOwner.withLock {
            val state = interceptBootstrapFetch("stream")
            try {
                assertFailsWith<IllegalStateException> { bootstrapLocalFrontend(LaunchOrigin, LaunchPath, LaunchTicket) }
                withTimeout(5_000) { while (!bootstrapStreamStopped(state)) delay(1) }
                assertEquals(1, bootstrapFetchCount(state))
            } finally { restoreBootstrapFetch(state) }
        } }
    }
    test("bootstrap rejects malformed UTF-8, old protocol and surplus owner payload") {
        withContext(Dispatchers.Default) { bootstrapFetchOwner.withLock {
            for (mode in listOf("encoding", "old", "extra")) {
                val state = interceptBootstrapFetch(mode)
                try {
                    assertFailsWith<IllegalStateException> { bootstrapLocalFrontend(LaunchOrigin, LaunchPath, LaunchTicket) }
                    assertEquals(1, bootstrapFetchCount(state))
                } finally { restoreBootstrapFetch(state) }
            }
        } }
    }
}

@JsFun("""(mode) => {
    const original = globalThis.fetch;
    const target = 'http://127.0.0.1:1/web/' + 'a'.repeat(64) + '/bootstrap';
    const state = {calls: 0, clean: true, pulls: 0, cancelled: false};
    const replacement = async function(input, init) {
        const url = typeof input === 'string' ? input : input.url;
        if (url !== target) return original.call(globalThis, input, init);
        state.calls++;
        const body = JSON.parse(await new Response(init.body).text());
        state.clean &&= !new Headers(init.headers).has('Authorization') && init.method === 'POST'
            && Object.keys(body).length === 1 && body.ticket === 'b'.repeat(64) && init.redirect === 'manual';
        if (mode === 'lost') throw new TypeError('Failed to fetch');
        if (mode === 'encoding') return new Response(new Uint8Array([0xc3, 0x28]));
        if (mode === 'stream') return new Response(new ReadableStream({
            pull(controller) {
                if (++state.pulls > 1024) controller.close();
                else controller.enqueue(new Uint8Array(8192).fill(32));
            },
            cancel() { state.cancelled = true; }
        }));
        const result = {
            protocol: mode === 'old' ? 2 : 3,
            workingDirectory: '/project',
            endpoint: {instanceId: 'direct-test', port: 1234, password: 'c'.repeat(64), businessProtocol: '0.5.7'}
        };
        if (mode === 'extra') result.host = {};
        return new Response(JSON.stringify(result));
    };
    state.restore = () => {
        if (globalThis.fetch !== replacement) throw new Error('Fetch ownership changed');
        globalThis.fetch = original;
    };
    globalThis.fetch = replacement;
    return state;
}""")
private external fun interceptBootstrapFetch(mode: String): JsAny

@JsFun("(state) => state.calls")
private external fun bootstrapFetchCount(state: JsAny): Int

@JsFun("(state) => state.clean")
private external fun bootstrapFetchClean(state: JsAny): Boolean

@JsFun("(state) => state.cancelled && state.pulls < 1024")
private external fun bootstrapStreamStopped(state: JsAny): Boolean

@JsFun("(state) => state.restore()")
private external fun restoreBootstrapFetch(state: JsAny)
