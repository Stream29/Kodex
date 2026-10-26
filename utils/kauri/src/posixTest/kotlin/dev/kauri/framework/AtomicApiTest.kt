@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kauri.framework

import kotlinx.cinterop.*
import platform.posix.*
import kotlin.test.*

private class BackgroundProbe(val window: WebViewWindow) { var failure: Throwable? = null }
private fun runOffMain(pointer: COpaquePointer?): COpaquePointer? {
    val probe = checkNotNull(pointer).asStableRef<BackgroundProbe>().get()
    probe.failure = runCatching { probe.window.run() }.exceptionOrNull()
    probe.window.requestClose()
    return null
}

class AtomicApiTest {
    @Test fun exactLoopbackOrigin() {
        assertEquals("http://127.0.0.1:8421", localOrigin("http://127.0.0.1:8421/path?q=1#secret"))
        listOf(
            "http://localhost:8421/", "https://127.0.0.1:8421/", "http://127.0.0.1:0/",
            "http://127.0.0.1:65536/", "http://127.0.0.1/", "http://127.0.0.1:8421@evil/",
            "http://127.0.0.1:8421.evil/", "http://127.0.0.1:8421/\\evil",
        ).forEach { assertNull(localOrigin(it)) }
        assertFalse(allowsDocument("http://127.0.0.1:8422/", "http://127.0.0.1:8421"))
        assertTrue(allowsDocument("http://127.0.0.1/", "http://127.0.0.1:80"))
        assertTrue(allowsDocument("http://127.0.0.1:00080/", "http://127.0.0.1:80"))
        assertFalse(allowsDocument("http://127.0.0.1/", "http://127.0.0.1:8421"))
        assertFalse(allowsDocument("http://127.0.0.1:80@foreign.invalid/", "http://127.0.0.1:80"))
    }

    @Test fun constructionAndCancellationNeedNoAssetsOrServer() {
        val window = WebViewWindow("http://127.0.0.1:1/#private", "Independent")
        window.requestClose()
        window.requestClose()
        window.run()
        window.requestClose()
        assertFailsWith<IllegalStateException> { window.run() }
    }

    @Test fun rejectsInvalidConfigurationWithoutLeakingCapability() {
        assertFailsWith<IllegalArgumentException> { WebViewWindow("file:///secret", "Test") }
        assertFailsWith<IllegalArgumentException> { WebViewWindow("http://127.0.0.1:1/", "") }
        val failure = assertFailsWith<IllegalArgumentException> { WebViewWindow("https://evil/#private", "Test") }
        assertFalse(failure.message.orEmpty().contains("private"))
    }

    @Test fun rejectsWorkerThreadAndAcceptsItsCloseRequest() = memScoped {
        val window = WebViewWindow("http://127.0.0.1:1/", "No native resources")
        val probe = BackgroundProbe(window)
        val reference = StableRef.create(probe)
        try {
            val worker = alloc<pthread_tVar>()
            assertEquals(0, pthread_create(worker.ptr, null, staticCFunction(::runOffMain), reference.asCPointer()))
            assertEquals(0, pthread_join(worker.value, null))
            assertIs<IllegalStateException>(probe.failure)
            window.run()
        } finally { reference.dispose() }
    }

    @Test fun resourcePackValidationDoesNotLinkApplicationAssets() {
        val archive = byteArrayOf(0x4b, 0x41, 0x55, 0x52, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 2, 97, 98, 99)
        fun lookup(bytes: ByteArray, path: String) =
            findResource(bytes.size.toLong(), { bytes[it].toInt() and 255 }, path)
        assertEquals(17 to 2, lookup(archive, "a"))
        assertNull(lookup(archive, "missing"))
        assertFailsWith<IllegalStateException> { lookup(archive.copyOf(18), "a") }
        assertFailsWith<IllegalStateException> { lookup(archive + 0, "a") }
        assertFailsWith<IllegalStateException> { lookup(archive.copyOf().also { it[7] = -1 }, "a") }
    }
}
