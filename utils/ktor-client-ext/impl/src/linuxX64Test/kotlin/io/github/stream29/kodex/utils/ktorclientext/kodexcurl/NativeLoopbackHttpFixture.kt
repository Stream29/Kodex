@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.stream29.kodex.utils.ktorclientext.kodexcurl

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.posix.*
import kotlin.time.Duration.Companion.seconds

/**
 * One test-owned loopback listener, ephemeral port and accepted peer.
 * Blocking socket operations run only on test-owned IO children and have
 * native receive/send timeouts; no external server or user process is used.
 */
internal suspend fun <T> withNativeHttpFixture(
    bytes: ByteArray,
    advertisedLength: Int = bytes.size,
    block: suspend (NativeHttpFixture) -> T,
): T = withContext(Dispatchers.IO) {
    coroutineScope {
        val listener = socket(AF_INET, SOCK_STREAM, 0)
        check(listener >= 0) { "Test socket: errno $errno" }
        var server: Job? = null
        val allowPeerClose = CompletableDeferred<Unit>()
        try {
            val port = memScoped {
                val timeout = alloc<timeval>().apply { tv_sec = 5; tv_usec = 0 }
                check(setsockopt(listener, SOL_SOCKET, SO_RCVTIMEO, timeout.ptr, sizeOf<timeval>().convert()) == 0)
                check(setsockopt(listener, SOL_SOCKET, SO_SNDTIMEO, timeout.ptr, sizeOf<timeval>().convert()) == 0)
                val address = alloc<sockaddr_in>().apply {
                    sin_family = AF_INET.convert()
                    sin_port = 0u
                    sin_addr.s_addr = htonl(INADDR_LOOPBACK)
                }
                check(bind(listener, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0)
                check(listen(listener, 1) == 0)
                val length = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
                check(getsockname(listener, address.ptr.reinterpret(), length.ptr) == 0)
                ntohs(address.sin_port).toInt()
            }
            val sent = CompletableDeferred<Unit>()
            server = launch(Dispatchers.IO) {
                var peer = -1
                try {
                    peer = accept(listener, null, null)
                    check(peer >= 0) { "Test accept: errno $errno" }
                    memScoped {
                        val timeout = alloc<timeval>().apply { tv_sec = 5; tv_usec = 0 }
                        check(setsockopt(peer, SOL_SOCKET, SO_RCVTIMEO, timeout.ptr, sizeOf<timeval>().convert()) == 0)
                        check(setsockopt(peer, SOL_SOCKET, SO_SNDTIMEO, timeout.ptr, sizeOf<timeval>().convert()) == 0)
                    }
                    receiveRequestHeaders(peer)
                    val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                        "Content-Length: $advertisedLength\r\nConnection: close\r\n\r\n"
                    sendAll(peer, headers.encodeToByteArray())
                    sendAll(peer, bytes)
                    sent.complete(Unit)
                    withTimeout(10.seconds) { allowPeerClose.await() }
                } catch (failure: Throwable) {
                    sent.completeExceptionally(failure)
                    if (!allowPeerClose.isCompleted) throw failure
                } finally {
                    if (peer >= 0) {
                        shutdown(peer, SHUT_RDWR)
                        close(peer)
                    }
                }
            }
            block(NativeHttpFixture("http://127.0.0.1:$port/", sent, allowPeerClose))
        } finally {
            withContext(NonCancellable) {
                allowPeerClose.complete(Unit)
                shutdown(listener, SHUT_RDWR) // Unblock an accept if startup failed.
                server?.cancelAndJoin()
                close(listener)
            }
        }
    }
}

internal class NativeHttpFixture(
    val url: String,
    val sent: Deferred<Unit>,
    private val allowPeerClose: CompletableDeferred<Unit>,
) {
    fun closePeerAfterHeaders() { allowPeerClose.complete(Unit) }
}

private fun receiveRequestHeaders(peer: Int) {
    val bytes = ByteArray(4096)
    val headers = StringBuilder()
    while ("\r\n\r\n" !in headers) {
        val count = bytes.usePinned { recv(peer, it.addressOf(0), bytes.size.convert(), 0) }.toInt()
        if (count < 0 && errno == EINTR) continue
        check(count > 0) { "Test receive: errno $errno" }
        headers.append(bytes.decodeToString(endIndex = count))
        check(headers.length < 65_536) { "Test request header bound exceeded" }
    }
}

private fun sendAll(peer: Int, bytes: ByteArray) {
    var offset = 0
    bytes.usePinned { pinned ->
        while (offset < bytes.size) {
            val count = send(peer, pinned.addressOf(offset), (bytes.size - offset).convert(), MSG_NOSIGNAL).toInt()
            if (count < 0 && errno == EINTR) continue
            check(count > 0) { "Test send: errno $errno" }
            offset += count
        }
    }
}
