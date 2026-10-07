package io.github.stream29.kodex.agentsession.filesystem

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.agentstorage.filesystem.FileSystemAgentStorage
import io.github.stream29.kodex.agentstorage.filesystem.FileSystemAgentStorageTimelineDirectories
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSource
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Every fallible initialization mutation plus both storage-path resolutions.
// Missing-pointer IOException is deliberately tolerated by the original reader;
// pointer reads are therefore tested as cancellation boundaries only.
private val initializationFaults: List<String> =
    listOf("storage-create", "resolve-1", "resolve-2") +
        FileSystemAgentStorageTimelineDirectories.flatMap { name ->
            listOf("mkdir-$name", "write-$name", "move-$name", "delete-$name")
        }

val fileSystemSessionCleanupTest by testSuite {
    testFixture {
        val root = Path(SystemTemporaryDirectory, "kodex-session-cleanup-${Random.nextLong()}")
        SystemCoroutineFileSystem.createDirectories(root, mustCreate = true)
        SystemCoroutineFileSystem.resolve(root)
    } closeWith {
        // Never use an injected delete failure to tear down the fixture.
        deleteRecursively(this, SystemCoroutineFileSystem)
    } asParameterForEach {
        for (cancelled in listOf(false, true)) {
            val boundaries = initializationFaults + if (cancelled) {
                FileSystemAgentStorageTimelineDirectories.map { "read-$it" }
            } else emptyList()
            for (boundary in boundaries) {
                test("create $boundary ${if (cancelled) "cancellation" else "IOException"} cleans exact reservation") { root ->
                    val primary = if (cancelled) CancellationException("injected $boundary")
                    else IOException("injected $boundary")
                    val target = Path(root, "sessions/1")
                    val fileSystem = SessionCleanupFaultFileSystem(target, boundary, primary)
                    val repository = FileSystemKodexSessionRepository(
                        root, testKodexAgentDependencies(), fileSystem,
                    )
                    try {
                        val preserved = prepareCleanupSource(root, repository)

                        assertSame(primary, captureSessionFailure { repository.create() })

                        assertEquals(emptyList(), primary.suppressedExceptions)
                        assertTrue(fileSystem.faultInjected)
                        assertTrue(fileSystem.deletedPaths.isNotEmpty())
                        assertEquals(target, fileSystem.deletedPaths.last())
                        assertTrue(fileSystem.deletedPaths.all { path ->
                            generateSequence(path) { it.parent }.any { it == target }
                        })
                        assertFalse(SystemCoroutineFileSystem.exists(target))
                        assertEquals(listOf(0), repository.list())
                        assertPreservedFiles(preserved)
                        // A disk scan before retry must not discover a half-created entry.
                        val reopened = FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
                        try {
                            assertEquals(listOf(0), reopened.list())
                        } finally {
                            reopened.coroutineContext[Job]!!.cancelAndJoin()
                        }
                        assertEquals(1, repository.create())
                        assertEmptySessionPointers(target)
                    } finally {
                        repository.coroutineContext[Job]!!.cancelAndJoin()
                    }
                }
            }
        }

        for (cancelled in listOf(false, true)) {
            test("create preserves ${if (cancelled) "cancellation" else "IOException"} when target delete fails") { root ->
                val primary = if (cancelled) CancellationException("injected initialization")
                else IOException("injected initialization")
                val cleanup = IOException("injected target deletion")
                val target = Path(root, "sessions/1")
                val fileSystem = SessionCleanupFaultFileSystem(target, "write-settings", primary, cleanup)
                val repository = FileSystemKodexSessionRepository(root, testKodexAgentDependencies(), fileSystem)
                try {
                    val preserved = prepareCleanupSource(root, repository)

                    assertSame(primary, captureSessionFailure { repository.create() })

                    assertEquals(1, primary.suppressedExceptions.size)
                    assertSame(cleanup, primary.suppressedExceptions.single())
                    assertEquals(listOf(0), repository.list())
                    assertPreservedFiles(preserved)
                    // Deliberate cleanup failure leaves an unadvertised target; no recovery policy added.
                    assertTrue(SystemCoroutineFileSystem.exists(target))
                } finally {
                    repository.coroutineContext[Job]!!.cancelAndJoin()
                }
            }

            for (failedCleanup in listOf(false, true)) {
                test("fork copy ${if (cancelled) "cancellation" else "IOException"} with delete failure=$failedCleanup") { root ->
                    val primary = if (cancelled) CancellationException("injected fork copy")
                    else IOException("injected fork copy")
                    val cleanup = if (failedCleanup) IOException("injected target deletion") else null
                    val target = Path(root, "sessions/1")
                    val fileSystem = SessionCleanupFaultFileSystem(target, "fork-copy", primary, cleanup)
                    val repository = FileSystemKodexSessionRepository(root, testKodexAgentDependencies(), fileSystem)
                    try {
                        val preserved = prepareCleanupSource(root, repository)

                        assertSame(primary, captureSessionFailure { repository.createFork(0) })

                        assertTrue(fileSystem.faultInjected)
                        assertEquals(listOf(0), repository.list())
                        assertPreservedFiles(preserved)
                        if (cleanup != null) {
                            assertEquals(1, primary.suppressedExceptions.size)
                            assertSame(cleanup, primary.suppressedExceptions.single())
                        } else {
                            assertEquals(emptyList(), primary.suppressedExceptions)
                            assertFalse(SystemCoroutineFileSystem.exists(target))
                            assertEquals(1, repository.createFork(0))
                            assertEquals(
                                SystemCoroutineFileSystem.readBytes(Path(root, "sessions/0/settings/0.json")).toList(),
                                SystemCoroutineFileSystem.readBytes(Path(target, "settings/0.json")).toList(),
                            )
                        }
                    } finally {
                        repository.coroutineContext[Job]!!.cancelAndJoin()
                    }
                }
            }
        }
    }
}

private suspend fun prepareCleanupSource(
    root: Path,
    repository: FileSystemKodexSessionRepository,
): Map<Path, List<Byte>> {
    assertEquals(0, repository.create())
    val source = Path(root, "sessions/0")
    // Direct storage only: no Session.open(), lease, runtime, process or running service.
    FileSystemAgentStorage(source).initialize(
        KodexAgentSettings(model = OpenAiModelId("test-model"), cwd = root, threadName = "source"),
    )
    SystemCoroutineFileSystem.createDirectories(Path(source, "subagents/legacy"))
    SystemCoroutineFileSystem.writeString(Path(source, "subagents/legacy/user.bin"), "legacy bytes")
    SystemCoroutineFileSystem.writeString(Path(source, "unknown.bin"), "source unknown bytes")
    SystemCoroutineFileSystem.writeString(Path(root, "sessions/.user-data"), "hidden sibling bytes")
    SystemCoroutineFileSystem.writeString(Path(root, "unknown.bin"), "home unknown bytes")
    return snapshotCleanupFiles(root)
}

private suspend fun snapshotCleanupFiles(path: Path): Map<Path, List<Byte>> {
    if (SystemCoroutineFileSystem.metadataOrNull(path)?.isDirectory != true) {
        return mapOf(path to SystemCoroutineFileSystem.readBytes(path).toList())
    }
    val files = mutableMapOf<Path, List<Byte>>()
    for (child in SystemCoroutineFileSystem.list(path)) files.putAll(snapshotCleanupFiles(child))
    return files
}

private suspend fun assertPreservedFiles(files: Map<Path, List<Byte>>) {
    for ((path, bytes) in files) {
        assertEquals(bytes, SystemCoroutineFileSystem.readBytes(path).toList(), path.toString())
    }
}

private suspend fun assertEmptySessionPointers(target: Path) {
    for (name in FileSystemAgentStorageTimelineDirectories) {
        assertEquals("-1", SystemCoroutineFileSystem.readString(Path(target, "$name/latest.json")))
        assertEquals(listOf("latest.json"), SystemCoroutineFileSystem.list(Path(target, name)).map { it.name })
    }
}

private suspend fun captureSessionFailure(operation: suspend () -> Unit): Throwable = coroutineScope {
    val observed = CompletableDeferred<Throwable>()
    launch {
        observed.complete(runCatching { operation() }.exceptionOrNull() ?: error("Expected failure"))
    }.join()
    observed.await()
}

private class SessionCleanupFaultFileSystem(
    private val target: Path,
    private val boundary: String,
    private val primaryFailure: Throwable,
    private val cleanupFailure: Throwable? = null,
    private val delegate: CoroutineFileSystem = SystemCoroutineFileSystem,
) : CoroutineFileSystem by delegate {
    private var resolveCalls = 0
    private var operationJob: Job? = null
    var faultInjected: Boolean = false
        private set
    val deletedPaths: MutableList<Path> = mutableListOf()

    override suspend fun createDirectories(path: Path, mustCreate: Boolean) {
        delegate.createDirectories(path, mustCreate)
        if (path == target && !mustCreate) {
            operationJob = currentCoroutineContext()[Job]
            hit("storage-create")
        }
        timelineName(path)?.let { hit("mkdir-$it") }
    }

    override suspend fun resolve(path: Path): Path {
        val resolved = delegate.resolve(path)
        if (path == target) hit("resolve-${++resolveCalls}")
        return resolved
    }

    override suspend fun readString(path: Path): String {
        if (path.name == "latest.json") timelineName(path.parent)?.let { hit("read-$it") }
        return delegate.readString(path)
    }

    override suspend fun writeString(path: Path, content: String, append: Boolean, mustCreate: Boolean) {
        delegate.writeString(path, content, append, mustCreate)
        if (path.name.startsWith(".kodex-write-")) timelineName(path.parent)?.let { hit("write-$it") }
    }

    override suspend fun atomicMove(source: Path, destination: Path) {
        if (destination.name == "latest.json") timelineName(destination.parent)?.let { hit("move-$it") }
        delegate.atomicMove(source, destination)
    }

    override suspend fun delete(path: Path, mustExist: Boolean) {
        if (generateSequence(path) { it.parent }.any { it == target }) {
            // Both temporary and reserved-target cleanup must survive actual caller cancellation.
            currentCoroutineContext().ensureActive()
            deletedPaths += path
            if (path.name.startsWith(".kodex-write-")) timelineName(path.parent)?.let { hit("delete-$it") }
            if (path == target && faultInjected) cleanupFailure?.let { throw it }
        }
        delegate.delete(path, mustExist)
    }

    override suspend fun <R> useSource(
        path: Path,
        block: suspend (CoroutineRawSource) -> R,
    ): R {
        val result = delegate.useSource(path, block)
        // Fail the raw-copy operation after real target bytes were flushed and
        // both handles closed, outside dispatcher exception-recovery boundaries.
        if (path == Path(requireNotNull(target.parent), "0/settings/0.json")) hit("fork-copy")
        return result
    }

    private fun timelineName(path: Path?): String? =
        FileSystemAgentStorageTimelineDirectories.firstOrNull { path == Path(target, it) }

    private fun hit(actualBoundary: String) {
        if (faultInjected || actualBoundary != boundary) return
        faultInjected = true
        if (primaryFailure is CancellationException) operationJob!!.cancel(primaryFailure)
        throw primaryFailure
    }
}
