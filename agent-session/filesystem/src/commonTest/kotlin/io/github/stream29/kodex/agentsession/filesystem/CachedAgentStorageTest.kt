package io.github.stream29.kodex.agentsession.filesystem

import de.infix.testBalloon.framework.core.testSuite
import io.github.reactivecircus.cache4k.CacheEvent
import io.github.reactivecircus.cache4k.CacheEventListener
import io.github.reactivecircus.cache4k.FakeTimeSource
import io.github.stream29.kodex.agentstorage.filesystem.FileSystemIndexVersioned
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val FirstIndex: Int = 0

val cachedAgentStorageTest by testSuite {
    test("actively removes an idle value without a cache access") {
        withCachedTimeline { fixture ->
            assertEquals("first", fixture.cached[FirstIndex])
            assertEquals(1, fixture.fileSystem.contentReadCount)

            fixture.timeSource += 61.seconds
            fixture.events.awaitEvent { event ->
                event is CacheEvent.Expired && event.key == FirstIndex
            }

            assertEquals("first", fixture.cached[FirstIndex])
            assertEquals(2, fixture.fileSystem.contentReadCount)
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
    val cached: CachedIndexVersioned<String>,
    val timeSource: FakeTimeSource,
    val fileSystem: TrackingFileSystem,
    val ownerJob: kotlinx.coroutines.Job,
    val events: Channel<CacheEvent<Int, String>>,
)

private suspend inline fun <R> withCachedTimeline(
    valueCacheSize: Int = 1_024,
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
    delegate.setUnsafe(FirstIndex, "first")

    val ownerJob = SupervisorJob()
    val ownerScope = CoroutineScope(ownerJob)
    val timeSource = FakeTimeSource()
    val events = Channel<CacheEvent<Int, String>>(Channel.UNLIMITED)
    val cached = CachedIndexVersioned(
        ownerScope = ownerScope,
        delegate = delegate,
        valueCacheSize = valueCacheSize,
        indexes = listOf(FirstIndex),
        timeSource = timeSource,
        cleanupInterval = 1.milliseconds,
        cacheEventListener = CacheEventListener { event ->
            events.trySend(event)
        },
    )
    val fixture = CachedTimelineFixture(
        cached = cached,
        timeSource = timeSource,
        fileSystem = fileSystem,
        ownerJob = ownerJob,
        events = events,
    )
    return try {
        block(fixture)
    } finally {
        withContext(NonCancellable) {
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
    val contentReadStarted = CompletableDeferred<Unit>()
    val allowContentRead = CompletableDeferred<Unit>()

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
