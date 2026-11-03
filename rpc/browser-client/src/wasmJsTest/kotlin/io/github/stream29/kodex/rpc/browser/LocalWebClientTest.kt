@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.stream29.kodex.rpc.browser

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.withLock
import kotlin.js.JsAny
import kotlin.test.*

internal val localWebClientTest by testSuite {
    test("launch location accepts only canonical loopback context paths and bounded tickets") {
        val path = "/web/${"a".repeat(64)}/"
        validateLocalWebLocation("http://127.0.0.1:1234", path, "b".repeat(64))
        for (origin in listOf("http://localhost:1234", "https://127.0.0.1:1234", "http://remote:1234")) {
            assertFailsWith<IllegalArgumentException> { validateLocalWebLocation(origin, path, null) }
        }
        for (bad in listOf("/web/a/", "$path../", "$path?x=1", "/web/${"A".repeat(64)}/")) {
            assertFailsWith<IllegalArgumentException> { validateLocalWebLocation("http://127.0.0.1:1234", bad, null) }
        }
        assertFailsWith<IllegalArgumentException> { validateLocalWebLocation("http://127.0.0.1:1234", path, "short") }
    }
    test("cross-origin launch is rejected before contacting any listener") {
        assertFailsWith<IllegalArgumentException> {
            withLocalWebClient<Unit>("http://127.0.0.1:1", "/web/${"a".repeat(64)}/", null) { _, _ ->
                fail("No business connection should be opened")
            }
        }
    }
    test("bootstrap returns only a scoped direct endpoint and does not retry a lost response") {
        withContext(Dispatchers.Default) { fetchOwner.withLock {
            for (lostResponse in listOf(false, true)) {
                val state = interceptEntryFetch(lostResponse)
                try {
                    if (lostResponse) {
                        assertFailsWith<IllegalStateException> {
                            bootstrapLocalFrontend("http://127.0.0.1:1", "/web/${"a".repeat(64)}/", "b".repeat(64))
                        }
                    } else {
                        val info = bootstrapLocalFrontend("http://127.0.0.1:1", "/web/${"a".repeat(64)}/", "b".repeat(64))
                        assertEquals("direct-test", info.endpoint.instanceId)
                        assertEquals(1234, info.endpoint.port)
                        assertEquals("/project", info.workingDirectory)
                    }
                    assertEquals("bootstrap", entryFetchCalls(state))
                    assertTrue(entryFetchClean(state), "No management credential or bearer header; capability only in bootstrap JSON.")
                } finally { restoreEntryFetch(state) }
            }
        } }
    }
    test("bootstrap rejects an oversized streaming reply without prebuffering its remaining body") {
        withContext(Dispatchers.Default) { fetchOwner.withLock {
            val state = interceptStreamingBootstrap()
            try {
                assertFailsWith<IllegalStateException> {
                    bootstrapLocalFrontend(
                        "http://127.0.0.1:1", "/web/${"a".repeat(64)}/", "b".repeat(64),
                    )
                }
                withTimeout(5_000) {
                    while (!streamingBootstrapStopped(state)) delay(1)
                }
                assertTrue(streamingBootstrapStopped(state), "The bounded reader must cancel the unread response.")
            } finally {
                restoreStreamingBootstrap(state)
            }
        } }
    }
}

@JsFun("""() => {
    const original = globalThis.fetch;
    const state = {pulls: 0, cancelled: false};
    const replacement = async (input, init) => {
        const url = typeof input === 'string' ? input : input.url;
        if (url !== 'http://127.0.0.1:1/web/' + 'a'.repeat(64) + '/bootstrap') {
            return original.call(globalThis, input, init);
        }
        return new Response(new ReadableStream({
            pull(controller) {
                if (++state.pulls > 1024) controller.close();
                else controller.enqueue(new Uint8Array(8192).fill(32));
            },
            cancel() { state.cancelled = true; }
        }), {status: 200});
    };
    state.restore = () => {
        if (globalThis.fetch !== replacement) throw new Error('Fetch ownership changed');
        globalThis.fetch = original;
    };
    globalThis.fetch = replacement;
    return state;
}""")
private external fun interceptStreamingBootstrap(): JsAny

@JsFun("(state) => state.cancelled && state.pulls < 1024")
private external fun streamingBootstrapStopped(state: JsAny): Boolean

@JsFun("(state) => state.restore()")
private external fun restoreStreamingBootstrap(state: JsAny)

@JsFun("""(lost) => {
    const original = globalThis.fetch;
    const prefix = 'http://127.0.0.1:1/web/' + 'a'.repeat(64) + '/';
    const state = {calls: [], clean: true};
    const replacement = async function(input, init) {
        const url = typeof input === 'string' ? input : input.url;
        if (!url.startsWith(prefix)) return original.call(globalThis, input, init);
        const route = url.slice(prefix.length);
        state.calls.push(route);
        state.clean &&= !new Headers(init.headers).has('Authorization');
        if (route === 'bootstrap') {
            const body = await new Response(init.body).text();
            state.clean &&= init.method === 'POST' && JSON.parse(body).ticket === 'b'.repeat(64);
            if (lost) return Promise.reject(new TypeError('Failed to fetch'));
            return Promise.resolve(new Response(JSON.stringify({
                protocol: 2, workingDirectory: '/project',
                endpoint: {instanceId: 'direct-test', port: 1234, password: 'c'.repeat(64), businessProtocol: '0.5.4'},
                host: {
                    protocol: 1, hostInstanceId: 'h'.repeat(64), backendInstanceId: 'direct-test',
                    origin: 'http://127.0.0.1:1', baseUrl: 'http://127.0.0.1:1',
                    credential: 'd'.repeat(64), owner: {value: 'o'.repeat(64)},
                    executionEnvironmentId: 'test-device', launchDirectory: '/launch', leaseMillis: 60000,
                    capabilities: {directoryBrowsing:'Ready', contextPathEvaluation:'Ready',
                        oauthCallbacks:'Ready', frontendErrorObservation:'Ready'}
                }
            }), {status: 200}));
        }
        return Promise.resolve(new Response('{}', {status: 401}));
    };
    state.restore = () => {
        if (globalThis.fetch !== replacement) throw new Error('Fetch ownership changed');
        globalThis.fetch = original;
    };
    globalThis.fetch = replacement;
    return state;
}""")
private external fun interceptEntryFetch(lost: Boolean): JsAny

@JsFun("(state) => state.calls.join(',')")
private external fun entryFetchCalls(state: JsAny): String

@JsFun("(state) => state.clean")
private external fun entryFetchClean(state: JsAny): Boolean

@JsFun("(state) => state.restore()")
private external fun restoreEntryFetch(state: JsAny)
