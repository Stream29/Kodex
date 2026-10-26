package io.github.stream29.kodex.rpc.localserver

import de.infix.testBalloon.framework.core.testSuite
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.io.IOException
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

// Deliberately bypass URLSession close callbacks: assertions inspect the server's RFC6455 bytes.
internal suspend fun <R> withLoopbackBrowserWire(
    port: Int, origin: String, block: suspend (LoopbackBrowserWire) -> R,
): R = coroutineScope {
    val wire = LoopbackBrowserWire(port, origin, SupervisorJob(coroutineContext.job))
    var primary: Throwable? = null
    try {
        block(wire)
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        try { withContext(NonCancellable) { withTimeout(5.seconds) { wire.close() } } }
        catch (cleanup: Throwable) { if (primary == null) throw cleanup else primary.addSuppressed(cleanup) }
    }
}

internal class LoopbackBrowserWire(
    private val port: Int, private val origin: String, private val selectorJob: CompletableJob,
) {
    private val selector = SelectorManager(Dispatchers.Default + selectorJob)
    private val sockets = mutableListOf<Socket>()

    suspend fun browser(): BrowserWireSocket = withTimeout(3.seconds) {
        require(port in 1..65535)
        require('\r' !in origin && '\n' !in origin)
        val socket = aSocket(selector).tcp().connect("127.0.0.1", port)
        sockets += socket // Own it before the next suspension, including failed/cancelled handshakes.
        val peer = BrowserWireSocket(socket)
        peer.upgrade(port, origin)
        peer
    }

    suspend fun close() {
        var failure: Throwable? = null
        fun record(cause: Throwable) {
            val first = failure
            if (first == null) failure = cause else first.addSuppressed(cause)
        }
        try {
            for (socket in sockets) {
                try { socket.close() } catch (cause: Throwable) { record(cause) }
            }
            try { sockets.forEach { it.socketContext.join() } }
            catch (cause: Throwable) { record(cause) }
        } finally {
            try { selector.close() } catch (cause: Throwable) { record(cause) }
            try { selectorJob.cancelAndJoin() } catch (cause: Throwable) { record(cause) }
        }
        failure?.let { throw it }
    }
}

internal class BrowserWireSocket(socket: Socket) {
    private val input = socket.openReadChannel()
    private val output = socket.openWriteChannel(autoFlush = true)

    internal suspend fun upgrade(port: Int, origin: String) {
        // RFC6455 section 1.3's known key/accept pair avoids sharing the server's handshake code.
        output.writeStringUtf8(
            "GET /browser/rpc HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n" +
                "Origin: $origin\r\n\r\n"
        )
        check(input.httpLine() == "HTTP/1.1 101 Switching Protocols") { "Expected a real WebSocket upgrade." }
        val headers = mutableListOf<Pair<String, String>>()
        var bytes = 0
        while (true) {
            val line = input.httpLine()
            bytes += line.length + 2
            check(bytes <= 8192) { "Upgrade headers exceeded fixture limit." }
            if (line.isEmpty()) break
            check(headers.size < 32 && ':' in line) { "Invalid upgrade headers." }
            headers += line.substringBefore(':').lowercase() to line.substringAfter(':').trim()
        }
        fun header(name: String) = headers.filter { it.first == name }.singleOrNull()?.second
        check(header("upgrade").equals("websocket", ignoreCase = true))
        check(header("connection")?.split(',')?.any { it.trim().equals("upgrade", true) } == true)
        check(header("sec-websocket-accept") == "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=")
        check(headers.none { it.first in setOf("sec-websocket-extensions", "sec-websocket-protocol") })
    }

    suspend fun ping(payload: String) = withTimeout(3.seconds) {
        val bytes = payload.encodeToByteArray()
        require(bytes.size <= 125)
        send(9, bytes)
        val frame = input.serverFrame()
        check(frame.opcode == 10 && frame.payload.contentEquals(bytes)) { "Expected the matching wire Pong." }
    }

    suspend fun hello(text: String): String = withTimeout(3.seconds) {
        send(1, text.encodeToByteArray())
        val frame = input.serverFrame()
        check(frame.opcode == 1) {
            if (frame.opcode == 8) "Welcome rejected: ${frame.closeReason()}."
            else "Expected welcome Text, received opcode ${frame.opcode}."
        }
        frame.payload.decodeToString(throwOnInvalidSequence = true)
    }

    suspend fun rejectHello(text: String): WireClose = withTimeout(3.seconds) {
        send(1, text.encodeToByteArray())
        readClose()
    }

    suspend fun closeReason(): WireClose = withTimeout(3.seconds) { readClose() }

    private suspend fun readClose(): WireClose {
        val frame = input.serverFrame()
        val reason = frame.closeReason()
        try { send(8, frame.payload) } // Echo the validated close using a masked client frame.
        catch (_: IOException) {
            // The server may finish TCP after its Close; an echo write error cannot change the bytes already read.
        }
        return reason
    }

    private suspend fun send(opcode: Int, payload: ByteArray) {
        require(payload.size <= WireMaxPayload)
        val mask = Random.nextBytes(4)
        val extended = if (payload.size < 126) 0 else 2
        val frame = ByteArray(2 + extended + 4 + payload.size)
        frame[0] = (0x80 or opcode).toByte()
        frame[1] = (0x80 or if (extended == 0) payload.size else 126).toByte()
        if (extended != 0) {
            frame[2] = (payload.size ushr 8).toByte()
            frame[3] = payload.size.toByte()
        }
        mask.copyInto(frame, 2 + extended)
        for (index in payload.indices) {
            frame[2 + extended + 4 + index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
        }
        output.writeFully(frame)
        output.flush()
    }
}

internal data class WireClose(val code: Int, val reason: String)

private const val WireMaxPayload = 16 * 1024
private data class WireFrame(val opcode: Int, val payload: ByteArray)

private fun WireFrame.closeReason(): WireClose {
    check(opcode == 8) { "Expected a wire Close, received opcode $opcode." }
    check(payload.size >= 2) { "Close must contain an explicit status code." }
    val code = ((payload[0].toInt() and 255) shl 8) or (payload[1].toInt() and 255)
    check((code in 1000..1014 && code !in setOf(1004, 1005, 1006)) || code in 3000..4999) {
        "Invalid wire close code $code."
    }
    return WireClose(code, payload.decodeToString(2, payload.size, throwOnInvalidSequence = true))
}

private suspend fun ByteReadChannel.httpLine(): String {
    val line = mutableListOf<Byte>()
    while (line.size < 1024) {
        val byte = readByte()
        if (byte == 13.toByte()) {
            check(readByte() == 10.toByte()) { "Expected HTTP CRLF." }
            return line.toByteArray().decodeToString(throwOnInvalidSequence = true)
        }
        check(byte != 10.toByte()) { "Expected HTTP CRLF." }
        line += byte
    }
    error("HTTP line exceeded fixture limit.")
}

private suspend fun ByteReadChannel.serverFrame(): WireFrame {
    val first = readByte().toInt() and 255
    val second = readByte().toInt() and 255
    check(first and 0x80 != 0 && first and 0x70 == 0) { "Expected FIN without extensions." }
    check(second and 0x80 == 0) { "RFC6455 server frames must not be masked." }
    val opcode = first and 15
    check(opcode in setOf(1, 8, 9, 10)) { "Unexpected opcode $opcode." }
    val marker = second and 127
    val length = when (marker) {
        126 -> (((readByte().toInt() and 255) shl 8) or (readByte().toInt() and 255)).toLong()
            .also { check(it >= 126) { "Non-minimal 16-bit frame length." } }
        127 -> {
            var size = 0L
            repeat(8) { index ->
                val byte = readByte().toInt() and 255
                if (index == 0) check(byte and 128 == 0) { "Invalid 64-bit frame length." }
                size = (size shl 8) or byte.toLong()
            }
            check(size >= 65536) { "Non-minimal 64-bit frame length." }
            size
        }
        else -> marker.toLong()
    }
    check(length <= WireMaxPayload) { "Frame exceeded fixture limit: $length." }
    if (opcode >= 8) check(length <= 125 && (opcode != 8 || length != 1L)) { "Invalid control length." }
    return WireFrame(opcode, readByteArray(length.toInt()))
}

internal val browserWireCodecTest by testSuite {
    test("wire close requires an explicit valid status and cannot normalize 1005 to 1013") {
        assertEquals(WireClose(1013, ""),
            ByteReadChannel(byteArrayOf(0x88.toByte(), 2, 3, 0xf5.toByte())).serverFrame().closeReason())
        for (bytes in listOf(
            byteArrayOf(0x88.toByte(), 0),
            byteArrayOf(0x88.toByte(), 1, 3),
            byteArrayOf(0x88.toByte(), 2, 3, 0xed.toByte()), // 1005 is not a wire status.
            byteArrayOf(0x88.toByte(), 2, 3), // Truncated status must fail, not synthesize one.
            byteArrayOf(0x88.toByte(), 3, 3, 0xf5.toByte(), 0xff.toByte()), // Invalid UTF-8 reason.
        )) assertFails { ByteReadChannel(bytes).serverFrame().closeReason() }
    }
    test("wire frames reject masking fragmentation invalid lengths and oversized allocations") {
        for (bytes in listOf(
            byteArrayOf(0x88.toByte(), 0x82.toByte(), 0, 0, 0, 0, 3, 0xf5.toByte()),
            byteArrayOf(8, 0),
            byteArrayOf(0xc8.toByte(), 0),
            byteArrayOf(0x83.toByte(), 0),
            byteArrayOf(0x88.toByte(), 126, 0, 126),
            byteArrayOf(0x81.toByte(), 126, 0, 1),
            byteArrayOf(0x81.toByte(), 126, 0x40, 1), // 16385 exceeds the read bound.
            byteArrayOf(0x81.toByte(), 127, 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0),
            byteArrayOf(0x81.toByte(), 127, 0, 0, 0, 0, 0, 0, 0, 126),
            byteArrayOf(0x81.toByte(), 127, 0, 0, 0, 0, 0, 1, 0, 0),
        )) assertFails { ByteReadChannel(bytes).serverFrame() }
    }
    test("wire text consumes the exact 16-bit length before the next close frame") {
        val payload = ByteArray(126) { it.toByte() }
        val input = ByteReadChannel(byteArrayOf(0x81.toByte(), 126, 0, 126) + payload +
            byteArrayOf(0x88.toByte(), 2, 3, 0xf5.toByte()))
        val frame = input.serverFrame()
        assertEquals(1, frame.opcode)
        assertContentEquals(payload, frame.payload)
        assertEquals(WireClose(1013, ""), input.serverFrame().closeReason())
    }
}
