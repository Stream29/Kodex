package io.github.stream29.kodex.agentsession.filesystem

import de.infix.testBalloon.framework.core.testSuite
import io.github.reactivecircus.cache4k.CacheEvent
import io.github.reactivecircus.cache4k.FakeTimeSource
import io.github.stream29.kodex.agentstorage.contract.CachedIndexVersioned
import io.github.stream29.kodex.agentstorage.filesystem.FileSystemIndexVersioned
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val FirstIndex: Int = 0

val cachedAgentStorageTest by testSuite {
    for (kind in listOf("io", "cancellation")) {
        test("failed rescan after published $kind closes cached timeline admission") {
            withCachedTimeline { fixture ->
                val primary = if (kind == "io") IOException("published entry cleanup")
                else CancellationException("published entry cleanup cancelled")
                val secondary = IOException("numbered record rescan")
                val nonce = fixture.cached.cacheNonce.value
                assertEquals("first", fixture.cached[0]) // Prime a value that must no longer be trusted.
                fixture.fileSystem.failNextTemporaryDelete = primary
                fixture.fileSystem.failNextList = secondary
                val append = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { fixture.cached[5] = "published" }.exceptionOrNull()
                }
                fixture.fileSystem.listFailureStarted.await()
                assertEquals("published", fixture.delegate.getUnsafe(5))
                val record = Path(fixture.root, "timeline/5.json")
                val durableBytes = SystemCoroutineFileSystem.readBytes(record)
                val pendingRead = async(start = CoroutineStart.UNDISPATCHED) {
                    assertFailsWith<IllegalStateException> { fixture.cached[0] }
                }
                val pendingWrite = async(start = CoroutineStart.UNDISPATCHED) {
                    assertFailsWith<IllegalStateException> { fixture.cached[6] = "next" }
                }
                val pendingRevert = async(start = CoroutineStart.UNDISPATCHED) {
                    assertFailsWith<IllegalStateException> { fixture.cached.revert(0) }
                }
                assertFalse(pendingRead.isCompleted)
                assertFalse(pendingWrite.isCompleted)
                assertFalse(pendingRevert.isCompleted)
                fixture.fileSystem.allowListFailure.complete(Unit)

                assertSame(primary, append.await())
                assertTrue(primary.suppressedExceptions.any { it === secondary })
                assertTrue(pendingRead.await().message.orEmpty().contains("authority is uncertain"))
                assertTrue(pendingWrite.await().message.orEmpty().contains("authority is uncertain"))
                assertTrue(pendingRevert.await().message.orEmpty().contains("authority is uncertain"))
                val scanCount = fixture.fileSystem.timelineListCount
                assertEquals(0, fixture.cached.latestIndex.value)
                assertEquals(nonce, fixture.cached.cacheNonce.value)
                assertFailsWith<IllegalStateException> { fixture.cached.latestIndex() }
                assertFailsWith<IllegalStateException> { fixture.cached.getExact(0) }
                assertFailsWith<IllegalStateException> { fixture.cached.getExact(5) }
                assertFailsWith<IllegalStateException> { fixture.cached.floorToIndex(5) }
                assertFailsWith<IllegalStateException> { fixture.cached.ceilToIndex(0) }
                assertFailsWith<IllegalStateException> { fixture.cached.indexesIn(0..9) }
                assertFailsWith<IllegalStateException> { fixture.cached.valuesIn(0..9) }
                assertFailsWith<IllegalStateException> { fixture.cached.indexesIn(IntRange.EMPTY) }
                assertFailsWith<IllegalStateException> { fixture.cached.valuesIn(IntRange.EMPTY) }
                assertFailsWith<IllegalStateException> { fixture.cached[5] = "replacement" }
                assertEquals(scanCount, fixture.fileSystem.timelineListCount, "Admission must not retry scanning.")
                assertTrue(fixture.ownerJob.isActive)
                assertTrue(fixture.cached.isActive)
                assertContentEquals(durableBytes, SystemCoroutineFileSystem.readBytes(record))
                assertFalse(SystemCoroutineFileSystem.exists(Path(fixture.root, "timeline/6.json")))
                assertEquals(listOf(0, 5), fixture.delegate.storedIndexes())

                fixture.ownerJob.cancelAndJoin()
                val nextOwner = SupervisorJob()
                try {
                    val reopened = CachedIndexVersionedImpl(
                        ownerScope = CoroutineScope(nextOwner),
                        delegate = fixture.delegate,
                        valueCacheSize = 1_024,
                        indexes = fixture.delegate.storedIndexes(),
                    )
                    assertEquals(5, reopened.latestIndex())
                    assertEquals("published", reopened[5])
                    reopened[6] = "next"
                    assertEquals(6, reopened.latestIndex())
                    assertContentEquals(durableBytes, SystemCoroutineFileSystem.readBytes(record))
                } finally {
                    withContext(NonCancellable) { nextOwner.cancelAndJoin() }
                }
            }
        }
    }
    test("published record survives cleanup failure and is not overwritten by the next append") {
        withCachedTimeline { fixture ->
            val cleanup = IOException("published entry temporary cleanup")
            fixture.fileSystem.failNextTemporaryDelete = cleanup
            assertSame(cleanup, assertFailsWith<IOException> { fixture.cached[5] = "published" })
            assertEquals("published", fixture.delegate.getUnsafe(5))
            assertEquals(5, fixture.cached.latestIndex.value)
            assertEquals("published", fixture.cached.getExact(5))
            assertFailsWith<IllegalStateException> { fixture.cached[5] = "replacement" }
            fixture.cached[6] = "next"
            assertEquals("published", fixture.delegate.getUnsafe(5))
            assertEquals(6, fixture.cached.latestIndex.value)
        }
    }
    test("read-only view shares metadata and all original timeline queries") {
        withCachedTimeline(initialEntries = listOf(0 to "first", 5 to "fifth")) { fixture ->
            val view: CachedIndexVersioned<String> = fixture.cached
            assertSame(fixture.cached.cacheNonce, view.cacheNonce)
            assertSame(fixture.cached.latestIndex, view.latestIndex)
            assertEquals(5, view.latestIndex())
            assertEquals("first", view[3])
            assertEquals(null, view.getExact(3))
            assertEquals("fifth", view.getExact(5))
            assertEquals(0, view.floorToIndex(3))
            assertEquals(5, view.ceilToIndex(3))
            assertEquals(listOf(0, 5), view.indexesIn(0..9))
            assertEquals(listOf(0 to "first", 5 to "fifth"), view.valuesIn(0..9))

            val nonce = view.cacheNonce.value
            fixture.cached[9] = "ninth"
            assertEquals(9, view.latestIndex.value)
            assertEquals("ninth", view.getExact(9))
            assertEquals(nonce, view.cacheNonce.value)
            fixture.cached.revert(9)
            assertEquals(5, view.latestIndex.value)
            assertNotEquals(nonce, view.cacheNonce.value)
            assertEquals(null, view.getExact(9))

            fixture.ownerJob.cancelAndJoin()
            assertFailsWith<IllegalStateException> { view.latestIndex() }
            assertFailsWith<IllegalStateException> { view.getExact(0) }
        }
    }

    for (entries in listOf(emptyList(), listOf(0 to "first", 5 to "fifth"))) {
        test("metadata initializes from stored indexes ${entries.map { it.first }}") {
            withCachedTimeline(initialEntries = entries) { fixture ->
                val expected = entries.lastOrNull()?.first ?: -1
                assertEquals(expected, fixture.cached.latestIndex.value)
                assertEquals(expected, fixture.cached.latestIndex.first())
                assertEquals(expected, fixture.cached.latestIndex())
                assertEquals(fixture.cached.cacheNonce.value, fixture.cached.cacheNonce.first())
            }
        }
    }

    test("append publishes the sparse tail without replacing cache identity") {
        withCachedTimeline { fixture ->
            val nonce = fixture.cached.cacheNonce.value
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2.seconds) {
                    val published = async(start = CoroutineStart.UNDISPATCHED) {
                        fixture.cached.latestIndex.first { it == 5 }
                    }
                    fixture.cached[5] = "fifth"
                    assertEquals(5, published.await())
                }
            }
            assertEquals(5, fixture.cached.latestIndex())
            assertEquals("fifth", fixture.cached.getExact(5))
            assertEquals(nonce, fixture.cached.cacheNonce.value)
        }
    }

    test("destructive revert publishes a different nonce and the remaining tail") {
        withCachedTimeline(initialEntries = listOf(0 to "first", 5 to "fifth", 9 to "ninth")) { fixture ->
            val originalNonce = fixture.cached.cacheNonce.value
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2.seconds) {
                    val changedNonce = async(start = CoroutineStart.UNDISPATCHED) {
                        fixture.cached.cacheNonce.first { it != originalNonce }
                    }
                    val changedTail = async(start = CoroutineStart.UNDISPATCHED) {
                        fixture.cached.latestIndex.first { it == 5 }
                    }
                    fixture.cached.revert(9)
                    assertNotEquals(originalNonce, changedNonce.await())
                    assertEquals(5, changedTail.await())
                }
            }
            assertEquals(5, fixture.cached.latestIndex())
            assertEquals(null, fixture.cached.getExact(9))
            val previousNonce = fixture.cached.cacheNonce.value
            fixture.cached.revert(0)
            assertEquals(-1, fixture.cached.latestIndex.value)
            assertEquals(-1, fixture.cached.latestIndex())
            assertNotEquals(previousNonce, fixture.cached.cacheNonce.value)
        }
    }

    test("a no-op or rejected revert preserves published metadata") {
        withCachedTimeline { fixture ->
            val nonce = fixture.cached.cacheNonce.value
            fixture.cached.revert(1)
            fixture.cached.revert(Int.MAX_VALUE)
            assertFailsWith<IllegalArgumentException> { fixture.cached.revert(-1) }
            assertEquals(0, fixture.cached.latestIndex.value)
            assertEquals(nonce, fixture.cached.cacheNonce.value)
        }
    }

    test("rebuilding a cache reads the same persisted tail but creates a new in-memory identity") {
        withCachedTimeline { fixture ->
            fixture.cached[5] = "fifth"
            val previousNonce = fixture.cached.cacheNonce.value
            fixture.ownerJob.cancelAndJoin()
            val nextOwner = SupervisorJob()
            try {
                val rebuilt = CachedIndexVersionedImpl(
                    ownerScope = CoroutineScope(nextOwner),
                    delegate = fixture.delegate,
                    valueCacheSize = 1_024,
                    indexes = fixture.delegate.storedIndexes(),
                )
                assertEquals(5, rebuilt.latestIndex.value)
                assertNotEquals(previousNonce, rebuilt.cacheNonce.value)
                assertEquals("fifth", rebuilt.getExact(5))
            } finally {
                withContext(NonCancellable) {
                    nextOwner.cancelAndJoin()
                }
            }
        }
    }

    test("failed backing append does not publish a new tail or cache identity") {
        withCachedTimeline { fixture ->
            val nonce = fixture.cached.cacheNonce.value
            fixture.fileSystem.failNextMove = true
            assertFailsWith<IOException> { fixture.cached[5] = "fifth" }
            assertEquals(0, fixture.cached.latestIndex.value)
            assertEquals(0, fixture.cached.latestIndex())
            assertEquals(nonce, fixture.cached.cacheNonce.value)
        }
    }

    test("failed backing revert does not publish replacement metadata") {
        withCachedTimeline(initialEntries = listOf(0 to "first", 5 to "fifth")) { fixture ->
            val nonce = fixture.cached.cacheNonce.value
            fixture.fileSystem.failNextMove = true
            assertFailsWith<IOException> { fixture.cached.revert(5) }
            assertEquals(5, fixture.cached.latestIndex.value)
            assertEquals(5, fixture.cached.latestIndex())
            assertEquals(nonce, fixture.cached.cacheNonce.value)
        }
    }

    test("actively removes an idle value without a cache access") {
        withCachedTimeline { fixture ->
            val nonce = fixture.cached.cacheNonce.value
            assertEquals("first", fixture.cached[FirstIndex])
            assertEquals(1, fixture.fileSystem.contentReadCount)

            fixture.timeSource += 61.seconds
            fixture.events.awaitEvent { event ->
                event is CacheEvent.Expired && event.key == FirstIndex
            }

            assertEquals("first", fixture.cached[FirstIndex])
            assertEquals(2, fixture.fileSystem.contentReadCount)
            assertEquals(nonce, fixture.cached.cacheNonce.value)
            assertEquals(FirstIndex, fixture.cached.latestIndex.value)
        }
    }

    test("cache hits refresh the value expiration time") {
        withCachedTimeline { fixture ->
            assertEquals("first", fixture.cached[FirstIndex])

            fixture.timeSource += 50.seconds
            assertEquals("first", fixture.cached[FirstIndex])
            fixture.timeSource += 50.seconds
            delay(20.milliseconds)

            assertFalse(
                fixture.events.drain().any { event ->
                    event is CacheEvent.Expired && event.key == FirstIndex
                },
            )
            assertEquals(1, fixture.fileSystem.contentReadCount)

            fixture.timeSource += 11.seconds
            fixture.events.awaitEvent { event ->
                event is CacheEvent.Expired && event.key == FirstIndex
            }
            assertEquals("first", fixture.cached[FirstIndex])
            assertEquals(2, fixture.fileSystem.contentReadCount)
        }
    }

    test("capacity eviction and value expiration work together") {
        withCachedTimeline(valueCacheSize = 1) { fixture ->
            val nonce = fixture.cached.cacheNonce.value
            assertEquals("first", fixture.cached[FirstIndex])

            fixture.cached[1] = "second"
            assertTrue(
                fixture.events.drain().any { event ->
                    event is CacheEvent.Evicted && event.key == FirstIndex
                },
            )

            assertEquals("first", fixture.cached[FirstIndex])
            assertEquals(2, fixture.fileSystem.contentReadCount)

            fixture.timeSource += 61.seconds
            fixture.events.awaitEvent { event ->
                event is CacheEvent.Expired && event.key == FirstIndex
            }
            assertEquals("first", fixture.cached[FirstIndex])
            assertEquals(3, fixture.fileSystem.contentReadCount)
            assertEquals(nonce, fixture.cached.cacheNonce.value)
            assertEquals(1, fixture.cached.latestIndex.value)
        }
    }

    test("owner cancellation stops cleanup and clears cached values") {
        withCachedTimeline { fixture ->
            assertEquals("first", fixture.cached[FirstIndex])

            fixture.ownerJob.cancelAndJoin()
            assertFalse(fixture.cached.isActive)
            assertTrue(
                fixture.events.drain().any { event ->
                    event is CacheEvent.Removed && event.key == FirstIndex
                },
            )

            fixture.timeSource += 61.seconds
            delay(20.milliseconds)
            assertFalse(
                fixture.events.drain().any { event ->
                    event is CacheEvent.Expired && event.key == FirstIndex
                },
            )
        }
    }

    test("loader completing after owner cancellation does not leave a cached value") {
        withCachedTimeline { fixture ->
            fixture.fileSystem.suspendContentReads = true
            val loading = async {
                assertFailsWith<IllegalStateException> {
                    fixture.cached[FirstIndex]
                }
            }
            fixture.fileSystem.contentReadStarted.await()

            fixture.ownerJob.cancelAndJoin()
            fixture.fileSystem.allowContentRead.complete(Unit)

            loading.await()
            assertTrue(
                fixture.events.drain().any { event ->
                    event is CacheEvent.Removed && event.key == FirstIndex
                },
            )
        }
    }
}

private class CachedTimelineFixture(
    val root: Path,
    val cached: CachedIndexVersionedImpl<String>,
    val delegate: FileSystemIndexVersioned<String>,
    val timeSource: FakeTimeSource,
    val fileSystem: TrackingFileSystem,
    val ownerJob: kotlinx.coroutines.Job,
    val events: Channel<CacheEvent<Int, String>>,
)

private suspend inline fun <R> withCachedTimeline(
    valueCacheSize: Int = 1_024,
    initialEntries: List<Pair<Int, String>> = listOf(FirstIndex to "first"),
    crossinline block: suspend (CachedTimelineFixture) -> R,
): R {
    val root = Path(
        SystemTemporaryDirectory,
        "kodex-cached-index-${Random.nextLong()}",
    )
    val fileSystem = TrackingFileSystem()
    val delegate = FileSystemIndexVersioned(
        directory = Path(root, "timeline"),
        serializer = String.serializer(),
        json = Json,
        fileSystem = fileSystem,
    )
    initialEntries.forEach { (index, value) -> delegate.setUnsafe(index, value) }

    val ownerJob = SupervisorJob()
    val ownerScope = CoroutineScope(ownerJob)
    val timeSource = FakeTimeSource()
    val events = Channel<CacheEvent<Int, String>>(Channel.UNLIMITED)
    val cached = CachedIndexVersionedImpl(
        ownerScope = ownerScope,
        delegate = delegate,
        valueCacheSize = valueCacheSize,
        indexes = initialEntries.map { it.first },
        timeSource = timeSource,
        cleanupInterval = 1.milliseconds,
        cacheEventListener = { event ->
            events.trySend(event)
        },
    )
    val fixture = CachedTimelineFixture(
        root = root,
        cached = cached,
        delegate = delegate,
        timeSource = timeSource,
        fileSystem = fileSystem,
        ownerJob = ownerJob,
        events = events,
    )
    return try {
        block(fixture)
    } finally {
        withContext(NonCancellable) {
            fileSystem.allowListFailure.complete(Unit)
            fileSystem.allowContentRead.complete(Unit)
            ownerJob.cancelAndJoin()
            events.close()
            deleteRecursively(root)
        }
    }
}

private class TrackingFileSystem(
    private val delegate: CoroutineFileSystem = SystemCoroutineFileSystem,
) : CoroutineFileSystem by delegate {
    var contentReadCount: Int = 0
        private set
    var suspendContentReads: Boolean = false
    var failNextMove: Boolean = false
    var failNextTemporaryDelete: Throwable? = null
    var failNextList: Throwable? = null
    var timelineListCount: Int = 0
        private set
    private var publishedFaultTarget = false
    val listFailureStarted = CompletableDeferred<Unit>()
    val allowListFailure = CompletableDeferred<Unit>()
    val contentReadStarted = CompletableDeferred<Unit>()
    val allowContentRead = CompletableDeferred<Unit>()

    override suspend fun list(directory: Path): Collection<Path> {
        timelineListCount += 1
        failNextList?.let { failure ->
            failNextList = null
            listFailureStarted.complete(Unit)
            allowListFailure.await()
            throw failure
        }
        return delegate.list(directory)
    }

    override suspend fun delete(path: Path, mustExist: Boolean) {
        if (publishedFaultTarget && path.name.endsWith(".tmp")) {
            failNextTemporaryDelete?.let { failure ->
                failNextTemporaryDelete = null
                publishedFaultTarget = false
                throw failure
            }
        }
        delegate.delete(path, mustExist)
    }

    override suspend fun atomicMove(source: Path, destination: Path) {
        if (failNextMove) {
            failNextMove = false
            throw IOException("Injected metadata test failure before move")
        }
        delegate.atomicMove(source, destination)
        if (failNextTemporaryDelete != null && destination.name == "5.json") {
            publishedFaultTarget = true
        }
    }

    override suspend fun readString(path: Path): String {
        if (path.name != "latest.json") {
            contentReadCount += 1
            if (suspendContentReads) {
                contentReadStarted.complete(Unit)
                allowContentRead.await()
            }
        }
        return delegate.readString(path)
    }
}

private suspend fun Channel<CacheEvent<Int, String>>.awaitEvent(
    predicate: (CacheEvent<Int, String>) -> Boolean,
): CacheEvent<Int, String> = withContext(Dispatchers.Default.limitedParallelism(1)) {
    withTimeout(2.seconds) {
        var matched: CacheEvent<Int, String>? = null
        while (matched == null) {
            val event = receive()
            if (predicate(event)) matched = event
        }
        matched
    }
}

private fun <T> Channel<T>.drain(): List<T> = buildList {
    while (true) {
        tryReceive().getOrNull()?.let(::add) ?: return@buildList
    }
}

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { child -> deleteRecursively(child) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
