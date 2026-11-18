@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.stream29.kodex.rpc.browser

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.backend.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.withLock
import kotlin.js.JsAny
import kotlin.test.*

private val transportCapabilities = FrontendHostCapabilities(
    FrontendCapabilityStatus.Ready, FrontendCapabilityStatus.Ready,
)
private fun transportDescriptor(credential: String = "c".repeat(64)) = LocalFrontendHostDescriptor(
    1, "i".repeat(64), "business-instance", "http://localhost:18795", "http://127.0.0.1:1234",
    credential, LocalFrontendOwner("o".repeat(64)), "device", "/launch", 60_000, transportCapabilities,
)

internal val frontendHostTransportTest by testSuite {
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
            withLocalFrontendClient<Unit>("http://127.0.0.1:1", "/web/${"a".repeat(64)}/", null) { _, _, _ ->
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
    test("actual fetch sends only the closed typed call and correlates the exact reply") {
        withContext(Dispatchers.Default) { fetchOwner.withLock {
            val state = interceptCompanionFetch("valid")
            val http = HttpClient(Js) { followRedirects = false }
            try {
                val host = frontendHostConnection(transportDescriptor(), http, MutableStateFlow(transportCapabilities))
                assertSame(
                    LocalFrontendHostReply.OwnerClosed,
                    host.call(LocalFrontendHostRequest.CloseOwner),
                )
                assertEquals(1, companionFetchCount(state))
                assertTrue(companionFetchClean(state))
            } finally { http.close(); restoreCompanionFetch(state) }
        } }
    }
    test("wrong instance owner checkpoint and unknown fields fail without retry or secret diagnostics") {
        withContext(Dispatchers.Default) { fetchOwner.withLock {
            for (mode in listOf(
                "instance", "owner", "protocol", "unknown", "large", "utf8", "json", "wrong-reply",
                "denied", "forbidden", "server", "lost", "stream-lost", "js-invalid",
            )) {
                val state = interceptCompanionFetch(mode)
                val http = HttpClient(Js) { followRedirects = false }
                try {
                    val host = frontendHostConnection(transportDescriptor(), http, MutableStateFlow(transportCapabilities))
                    val failure = assertFails {
                        host.call(LocalFrontendHostRequest.CloseOwner)
                    }
                    assertFalse(failure.message.orEmpty().contains("private-callback-marker"))
                    assertEquals(1, companionFetchCount(state), "$mode must not retry an unknown outcome.")
                } finally { http.close(); restoreCompanionFetch(state) }
            }
        } }
    }
    test("only real fetch or HTTP 5xx failures are retry candidates and renew still sends once per call") {
        withContext(Dispatchers.Default) { fetchOwner.withLock {
            for (mode in listOf(
                "lost", "stream-lost", "server", "denied", "forbidden", "instance", "owner",
                "protocol", "unknown", "utf8", "json", "large", "wrong-reply-renew", "js-invalid",
            )) {
                val state = interceptCompanionFetch(mode)
                val http = HttpClient(Js) { followRedirects = false }
                try {
                    val host = frontendHostConnection(transportDescriptor(), http, MutableStateFlow(transportCapabilities))
                    val failure = assertFails { host.call(LocalFrontendHostRequest.RenewOwner) }
                    when (mode) {
                        "lost", "stream-lost", "server" -> assertIs<FrontendHostTransientFailure>(failure)
                        "denied", "forbidden" -> assertIs<FrontendHostRejected>(failure)
                        else -> assertIs<FrontendHostInvalidResponse>(failure)
                    }
                    assertEquals(1, companionFetchCount(state))
                    assertFalse(failure.toString().contains("private-"))
                    assertNull(failure.cause)
                } finally { http.close(); restoreCompanionFetch(state) }
            }
        } }
    }
    test("close cleanup never replays an unknown result or rejection") {
        withContext(Dispatchers.Default) { fetchOwner.withLock {
            for (mode in listOf("lost", "server", "denied", "forbidden")) {
                val state = interceptCompanionFetch(mode)
                val http = HttpClient(Js) { followRedirects = false }
                try {
                    val host = frontendHostConnection(transportDescriptor(), http, MutableStateFlow(transportCapabilities))
                    assertFails { host.call(LocalFrontendHostRequest.CloseOwner) }
                    assertEquals(1, companionFetchCount(state))
                    assertTrue(companionFetchClean(state))
                } finally { http.close(); restoreCompanionFetch(state) }
            }
        } }
    }
    test("renew response preserves typed permanent failure rather than treating it as network loss") {
        withContext(Dispatchers.Default) { fetchOwner.withLock {
            for (mode in listOf("renew-valid", "closed", "offline")) {
                val state = interceptCompanionFetch(mode)
                val http = HttpClient(Js)
                try {
                    val host = frontendHostConnection(transportDescriptor(), http, MutableStateFlow(transportCapabilities))
                    val reply = host.call(LocalFrontendHostRequest.RenewOwner)
                    when (mode) {
                        "renew-valid" -> assertEquals(60_000L, assertIs<LocalFrontendHostReply.OwnerRenewed>(reply).leaseMillis)
                        "closed" -> assertEquals(LocalFrontendHostFailure.OwnerClosed, assertIs<LocalFrontendHostReply.Failure>(reply).reason)
                        "offline" -> assertEquals(LocalFrontendHostFailure.BackendOffline, assertIs<LocalFrontendHostReply.Failure>(reply).reason)
                    }
                    assertEquals(1, companionFetchCount(state))
                } finally { http.close(); restoreCompanionFetch(state) }
            }
        } }
    }
    test("streaming frame read rejects over-budget input and 5xx without downloading an endless body") {
        withContext(Dispatchers.Default) { fetchOwner.withLock {
            for (mode in listOf("stream-large", "server-stream")) {
                val state = interceptCompanionFetch(mode)
                val http = HttpClient(Js)
                try {
                    val host = frontendHostConnection(transportDescriptor(), http, MutableStateFlow(transportCapabilities))
                    withTimeout(5_000) {
                        val failure = assertFails { host.call(LocalFrontendHostRequest.RenewOwner) }
                        if (mode == "server-stream") assertIs<FrontendHostTransientFailure>(failure)
                        else assertIs<FrontendHostInvalidResponse>(failure)
                        while (companionFetchStreamCancels(state) == 0) delay(1)
                    }
                    assertEquals(1, companionFetchCount(state))
                    assertTrue(companionFetchStreamPulls(state) < 100)
                } finally { http.close(); restoreCompanionFetch(state) }
            }
        } }
    }
    test("request timeout cancels actual pending fetch and is transient without a real lease-length wait") {
        withContext(Dispatchers.Default) { fetchOwner.withLock {
            val state = interceptCompanionFetch("pending")
            val http = HttpClient(Js) {
                install(HttpTimeout) { requestTimeoutMillis = 100 }
            }
            try {
                val host = frontendHostConnection(transportDescriptor(), http, MutableStateFlow(transportCapabilities))
                withTimeout(5_000) {
                    assertFailsWith<FrontendHostTransientFailure> { host.call(LocalFrontendHostRequest.RenewOwner) }
                    while (companionFetchAborts(state) == 0) delay(1)
                }
                assertEquals(1, companionFetchCount(state))
                assertEquals(1, companionFetchAborts(state))
            } finally { http.close(); restoreCompanionFetch(state) }
        } }
    }
    test("external cancellation remains cancellation and aborts fetch without retry") {
        withContext(Dispatchers.Default) { fetchOwner.withLock {
            val state = interceptCompanionFetch("pending")
            val http = HttpClient(Js)
            var request: Job? = null
            try {
                val host = frontendHostConnection(transportDescriptor(), http, MutableStateFlow(transportCapabilities))
                val ended = CompletableDeferred<Throwable>()
                val pending = launch {
                    try { host.call(LocalFrontendHostRequest.RenewOwner) }
                    catch (failure: Throwable) { ended.complete(failure) }
                }
                request = pending
                withTimeout(5_000) {
                    while (companionFetchCount(state) == 0) delay(1)
                    pending.cancelAndJoin()
                    assertIs<CancellationException>(ended.await())
                    while (companionFetchAborts(state) == 0) delay(1)
                }
                assertEquals(1, companionFetchCount(state))
                assertEquals(1, companionFetchAborts(state))
            } finally {
                withContext(NonCancellable) { request?.cancelAndJoin() }
                http.close()
                restoreCompanionFetch(state)
            }
        } }
    }
    test("oversize UTF8 call is rejected before fetch instead of truncating") {
        withContext(Dispatchers.Default) { fetchOwner.withLock {
            val state = interceptCompanionFetch("valid")
            val http = HttpClient(Js)
            try {
                val host = frontendHostConnection(
                    transportDescriptor(credential = "界".repeat(LocalFrontendHostMaxFrameBytes)),
                    http, MutableStateFlow(transportCapabilities),
                )
                assertFailsWith<IllegalArgumentException> {
                    host.call(LocalFrontendHostRequest.RenewOwner)
                }
                assertEquals(0, companionFetchCount(state))
            } finally { http.close(); restoreCompanionFetch(state) }
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
                endpoint: {instanceId: 'direct-test', port: 1234, password: 'c'.repeat(64), businessProtocol: '0.5.5'},
                host: {
                    protocol: 1, hostInstanceId: 'h'.repeat(64), backendInstanceId: 'direct-test',
                    origin: 'http://127.0.0.1:1', baseUrl: 'http://127.0.0.1:1',
                    credential: 'd'.repeat(64), owner: {value: 'o'.repeat(64)},
                    executionEnvironmentId: 'test-device', launchDirectory: '/launch', leaseMillis: 60000,
                    capabilities: {directoryBrowsing: 'Ready', contextPathEvaluation: 'Ready'}
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

@JsFun("""(mode) => {
    const original = globalThis.fetch;
    const state = {count: 0, aborts: 0, streamPulls: 0, streamCancels: 0, clean: true};
    const replacement = async function(input, init) {
        const url = typeof input === 'string' ? input : input.url;
        if (url !== 'http://127.0.0.1:1234/frontend-host') return original.call(globalThis, input, init);
        state.count++;
        const body = await new Response(init.body).text();
        const call = JSON.parse(body);
        state.clean &&= init.method === 'POST' && !new Headers(init.headers).has('Authorization')
            && call.protocol === 1 && call.hostInstanceId === 'i'.repeat(64)
            && call.owner.value === 'o'.repeat(64) && call.credential === 'c'.repeat(64)
            && ['renew_owner', 'close_owner'].includes(call.request.type)
            && !body.includes('management-private-marker') && !url.includes(call.credential);
        if (mode === 'lost') return Promise.reject(new TypeError('Failed to fetch'));
        if (mode === 'js-invalid') return Promise.reject(new SyntaxError('private-js-marker'));
        if (mode === 'pending') return new Promise((resolve, reject) => {
            const abort = () => {
                state.aborts++;
                reject(new DOMException('Aborted', 'AbortError'));
            };
            if (init.signal.aborted) abort();
            else init.signal.addEventListener('abort', abort, {once: true});
        });
        if (mode === 'stream-lost') return new Response(new ReadableStream({
            start(controller) { controller.error(new TypeError('private-stream-marker')); }
        }));
        if (mode === 'stream-large' || mode === 'server-stream') return new Response(new ReadableStream({
            pull(controller) { state.streamPulls++; controller.enqueue(new Uint8Array(4096)); },
            cancel() { state.streamCancels++; }
        }), {status: mode === 'server-stream' ? 503 : 200});
        const reply = {
            protocol: mode === 'protocol' ? 0 : 1,
            hostInstanceId: mode === 'instance' ? 'other' : 'i'.repeat(64),
            owner: {value: mode === 'owner' ? 'other' : 'o'.repeat(64)},
            reply: {type: 'owner_closed'}
        };
        if (mode === 'wrong-reply') reply.reply = {type: 'owner_renewed', leaseMillis: 60000, capabilities: {
            directoryBrowsing: 'Ready', contextPathEvaluation: 'Ready'
        }};
        if (mode === 'wrong-reply-renew') reply.reply = {type: 'owner_closed'};
        if (mode === 'renew-valid') reply.reply = {type: 'owner_renewed', leaseMillis: 60000, capabilities: {
            directoryBrowsing: 'Ready', contextPathEvaluation: 'Ready'
        }};
        if (mode === 'closed' || mode === 'offline') reply.reply = {
            type: 'failure', reason: mode === 'closed' ? 'OwnerClosed' : 'BackendOffline'
        };
        if (mode === 'unknown') reply.unknown = 'private-callback-marker';
        if (mode === 'utf8') return new Response(new Uint8Array([0xc3, 0x28]));
        const text = mode === 'large' ? ' '.repeat(65537)
            : mode === 'json' ? '{private-callback-marker' : JSON.stringify(reply);
        return new Response(text, {status: mode === 'denied' ? 401
            : mode === 'forbidden' ? 403 : mode === 'server' ? 503 : 200});
    };
    state.restore = () => {
        if (globalThis.fetch !== replacement) throw new Error('Fetch ownership changed');
        globalThis.fetch = original;
    };
    globalThis.fetch = replacement;
    return state;
}""")
private external fun interceptCompanionFetch(mode: String): JsAny

@JsFun("(state) => state.count")
private external fun companionFetchCount(state: JsAny): Int
@JsFun("(state) => state.aborts")
private external fun companionFetchAborts(state: JsAny): Int
@JsFun("(state) => state.streamPulls")
private external fun companionFetchStreamPulls(state: JsAny): Int
@JsFun("(state) => state.streamCancels")
private external fun companionFetchStreamCancels(state: JsAny): Int
@JsFun("(state) => state.clean")
private external fun companionFetchClean(state: JsAny): Boolean
@JsFun("(state) => state.restore()")
private external fun restoreCompanionFetch(state: JsAny)
