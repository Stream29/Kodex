package io.github.stream29.kodex.agentstorage.filesystem

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.builtins.serializer
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

private enum class WriteFault {
    EntryWrite, EntryMove, PointerWrite, PointerMove;

    val isEntry: Boolean get() = this == EntryWrite || this == EntryMove
    val isWrite: Boolean get() = this == EntryWrite || this == PointerWrite
}

val fileSystemWriteCleanupTest by testSuite {
    testFixture {
        Path(SystemTemporaryDirectory, "kodex-write-cleanup-${Random.nextLong()}").also {
            SystemCoroutineFileSystem.createDirectories(it, mustCreate = true)
        }
    } closeWith {
        // Bypass the fault wrapper even when the tested cleanup deliberately failed.
        deleteRecursively(SystemCoroutineFileSystem, this)
    } asParameterForEach {
        for (fault in WriteFault.entries) {
            for (cancelled in listOf(false, true)) {
                test("$fault preserves ${if (cancelled) "cancellation" else "IOException"} with failed delete") { directory ->
                    val primary = if (cancelled) CancellationException("injected $fault")
                    else IOException("injected $fault")
                    val cleanup = IOException("injected temporary delete")
                    val fileSystem = WriteCleanupFaultFileSystem(fault, primary, cleanup)
                    val storage = cleanupTimeline(directory, fileSystem)
                    if (fault.isEntry) cleanupTimeline(directory)[0] = 10

                    val observed = captureWriteFailure {
                        if (fault.isEntry) storage.setUnsafe(3, 30)
                        else storage.reconcileLatestIndexUnsafe(-1)
                    }

                    assertSame(primary, observed)
                    assertEquals(1, observed.suppressedExceptions.size)
                    assertSame(cleanup, observed.suppressedExceptions.single())
                    assertTrue(fileSystem.cleanupAttempted)
                    if (fault.isEntry) {
                        assertEquals("0", SystemCoroutineFileSystem.readString(Path(directory, "latest.json")))
                        assertEquals(10, cleanupTimeline(directory).getExact(0))
                        assertFalse(SystemCoroutineFileSystem.exists(Path(directory, "3.json")))
                    } else {
                        assertFalse(SystemCoroutineFileSystem.exists(Path(directory, "latest.json")))
                    }
                }

                test("$fault removes temporary after ${if (cancelled) "cancellation" else "IOException"}") { directory ->
                    val primary = if (cancelled) CancellationException("injected $fault")
                    else IOException("injected $fault")
                    val fileSystem = WriteCleanupFaultFileSystem(fault, primary, cleanupFailure = null)
                    val storage = cleanupTimeline(directory, fileSystem)
                    if (fault.isEntry) cleanupTimeline(directory)[0] = 10

                    assertSame(primary, captureWriteFailure {
                        if (fault.isEntry) storage.setUnsafe(3, 30)
                        else storage.reconcileLatestIndexUnsafe(-1)
                    })

                    assertTrue(fileSystem.cleanupAttempted)
                    assertTrue(SystemCoroutineFileSystem.list(directory).none {
                        it.name.startsWith(".kodex-write-")
                    })
                    // Retry using the same timeline: no journal/reopen policy is involved.
                    if (fault.isEntry) {
                        storage.setUnsafe(3, 30)
                        assertEquals(30, storage.getExact(3))
                    } else {
                        storage.reconcileLatestIndexUnsafe(-1)
                        assertEquals("-1", SystemCoroutineFileSystem.readString(Path(directory, "latest.json")))
                    }
                }
            }
        }

        for (fault in listOf(WriteFault.EntryWrite, WriteFault.PointerWrite)) {
            test("$fault successful publication exposes cleanup failure") { directory ->
                val cleanup = IOException("injected successful-operation cleanup")
                val storage = cleanupTimeline(
                    directory,
                    WriteCleanupFaultFileSystem(fault, primaryFailure = null, cleanupFailure = cleanup),
                )
                if (fault.isEntry) cleanupTimeline(directory)[0] = 10

                assertSame(cleanup, captureWriteFailure {
                    if (fault.isEntry) storage.setUnsafe(3, 30)
                    else storage.reconcileLatestIndexUnsafe(-1)
                })

                assertEquals(emptyList(), cleanup.suppressedExceptions)
                if (fault.isEntry) {
                    assertEquals(30, cleanupTimeline(directory).getExact(3))
                    assertEquals("3", SystemCoroutineFileSystem.readString(Path(directory, "latest.json")))
                } else {
                    assertEquals("-1", SystemCoroutineFileSystem.readString(Path(directory, "latest.json")))
                }
            }
        }

        for (cancelled in listOf(false, true)) {
            test("nested pointer move preserves ${if (cancelled) "cancellation" else "IOException"} with both deletes failing") { directory ->
                val primary = if (cancelled) CancellationException("injected pointer move")
                else IOException("injected pointer move")
                val pointerCleanup = IOException("injected pointer temporary delete")
                val entryCleanup = IOException("injected entry temporary delete")
                cleanupTimeline(directory)[0] = 10
                val storage = cleanupTimeline(
                    directory,
                    NestedWriteCleanupFaultFileSystem(primary, pointerCleanup, entryCleanup),
                )

                assertSame(primary, captureWriteFailure { storage.setUnsafe(3, 30) })

                assertEquals(2, primary.suppressedExceptions.size)
                assertSame(pointerCleanup, primary.suppressedExceptions[0])
                assertSame(entryCleanup, primary.suppressedExceptions[1])
                assertEquals("0", SystemCoroutineFileSystem.readString(Path(directory, "latest.json")))
                assertEquals(10, cleanupTimeline(directory).getExact(0))
                assertFalse(SystemCoroutineFileSystem.exists(Path(directory, "3.json")))
            }
        }
    }
}

private fun cleanupTimeline(
    directory: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): FileSystemIndexVersioned<Int> =
    FileSystemIndexVersioned(directory, Int.serializer(), OpenAiJsonCodec, fileSystem)

private suspend fun captureWriteFailure(operation: suspend () -> Unit): Throwable = coroutineScope {
    val observed = CompletableDeferred<Throwable>()
    launch {
        observed.complete(runCatching { operation() }.exceptionOrNull() ?: error("Expected failure"))
    }.join()
    observed.await()
}

private class WriteCleanupFaultFileSystem(
    private val fault: WriteFault,
    private val primaryFailure: Throwable?,
    private val cleanupFailure: Throwable?,
    private val delegate: CoroutineFileSystem = SystemCoroutineFileSystem,
) : CoroutineFileSystem by delegate {
    private var temporary: Path? = null
    private var operationFailed = false
    var cleanupAttempted: Boolean = false
        private set

    override suspend fun writeString(path: Path, content: String, append: Boolean, mustCreate: Boolean) {
        delegate.writeString(path, content, append, mustCreate)
        if (temporary == null && content == (if (fault.isEntry) "30" else "-1")) {
            temporary = path
            if (fault.isWrite) failOperation()
        }
    }

    override suspend fun atomicMove(source: Path, destination: Path) {
        if (!fault.isWrite && source == temporary) failOperation()
        delegate.atomicMove(source, destination)
    }

    override suspend fun delete(path: Path, mustExist: Boolean) {
        if (path == temporary && !cleanupAttempted) {
            cleanupAttempted = true
            // This must run even when failOperation cancelled the actual calling Job.
            currentCoroutineContext().ensureActive()
            cleanupFailure?.let { throw it }
        }
        delegate.delete(path, mustExist)
    }

    private suspend fun failOperation() {
        if (operationFailed || primaryFailure == null) return
        operationFailed = true
        if (primaryFailure is CancellationException) currentCoroutineContext().cancel(primaryFailure)
        throw primaryFailure
    }
}

private class NestedWriteCleanupFaultFileSystem(
    private val primaryFailure: Throwable,
    private val pointerCleanup: Throwable,
    private val entryCleanup: Throwable,
    private val delegate: CoroutineFileSystem = SystemCoroutineFileSystem,
) : CoroutineFileSystem by delegate {
    private var entryTemporary: Path? = null
    private var pointerTemporary: Path? = null
    private var operationFailed = false

    override suspend fun writeString(path: Path, content: String, append: Boolean, mustCreate: Boolean) {
        delegate.writeString(path, content, append, mustCreate)
        if (content == "30") entryTemporary = path
        if (content == "3") pointerTemporary = path
    }

    override suspend fun atomicMove(source: Path, destination: Path) {
        if (!operationFailed && source == pointerTemporary) {
            operationFailed = true
            if (primaryFailure is CancellationException) currentCoroutineContext().cancel(primaryFailure)
            throw primaryFailure
        }
        delegate.atomicMove(source, destination)
    }

    override suspend fun delete(path: Path, mustExist: Boolean) {
        currentCoroutineContext().ensureActive()
        if (path == pointerTemporary) throw pointerCleanup
        if (path == entryTemporary) throw entryCleanup
        delegate.delete(path, mustExist)
    }
}
