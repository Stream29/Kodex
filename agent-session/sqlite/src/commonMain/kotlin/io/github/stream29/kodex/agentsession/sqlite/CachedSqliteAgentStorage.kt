package io.github.stream29.kodex.agentsession.sqlite

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.*
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.*
import io.github.stream29.kodex.agentstorage.contract.*
import io.github.stream29.kodex.agentstorage.filesystem.FileSystemAgentStorage
import io.github.stream29.kodex.agentstorage.filesystem.FileSystemIndexVersioned
import io.github.stream29.kodex.agentstorage.sqlite.SqliteAgentStorage
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal class SequentialMirrorQueue(
    scope: CoroutineScope,
    private val reportFailure: (Throwable) -> Unit,
    capacity: Int = DefaultMirrorQueueCapacity,
    private val drainTimeout: Duration = DefaultMirrorDrainTimeout,
) {
    private val queue = Channel<suspend () -> Unit>(capacity)
    private val worker = scope.launch {
        try {
            for (write in queue) {
                try { write() }
                catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    runCatching { reportFailure(cancelled) }
                }
                catch (failure: Throwable) { runCatching { reportFailure(failure) } }
            }
        } finally { queue.cancel() }
    }.also { job -> job.invokeOnCompletion { queue.cancel() } }

    fun enqueue(write: suspend () -> Unit): Boolean = queue.trySend(write).isSuccess.also { accepted ->
        if (!accepted) runCatching { reportFailure(IOException("Session mirror is closed or backlog is full")) }
    }

    suspend fun closeAndDrain() {
        queue.close()
        val drained = withContext(Dispatchers.Default) {
            withTimeoutOrNull(drainTimeout) { worker.join(); true }
        }
        if (drained == null) {
            queue.cancel()
            worker.cancel()
            runCatching { reportFailure(IOException("Session mirror drain timed out after $drainTimeout")) }
        }
    }
}

internal class CachedSqliteAgentStorage(
    owner: CoroutineScope,
    private val backing: SqliteAgentStorage,
    private val directory: Path,
    private val fileSystem: CoroutineFileSystem,
    tails: Map<String, Int>,
    mirrorQueue: SequentialMirrorQueue,
) : MutableKodexAgentStorage, ObservableKodexAgentStorage {
    private suspend fun mirrorStorage(): FileSystemAgentStorage {
        fileSystem.createDirectories(directory)
        return FileSystemAgentStorage(directory, fileSystem)
    }
    override val uri: String get() = backing.uri
    override val index = CachedSqliteTimeline(owner, backing.index, { mirrorStorage().index }, tails["index"] ?: -1, mirrorQueue)
    override val work = CachedSqliteTimeline(owner, backing.work, { mirrorStorage().work }, tails["work"] ?: -1, mirrorQueue)
    override val settings = CachedSqliteTimeline(owner, backing.settings, { mirrorStorage().settings }, tails["settings"] ?: -1, mirrorQueue)
    override val timestamp = CachedSqliteTimeline(owner, backing.timestamp, { mirrorStorage().timestamp }, tails["timestamp"] ?: -1, mirrorQueue)
    override val tokenCount = CachedSqliteTimeline(owner, backing.tokenCount, { mirrorStorage().tokenCount }, tails["token_count"] ?: -1, mirrorQueue)
    override val unstable = CachedSqliteTimeline(owner, backing.unstable, { mirrorStorage().unstable }, tails["unstable"] ?: -1, mirrorQueue)
}

internal class CachedSqliteTimeline<T>(
    private val owner: CoroutineScope,
    private val source: MutableIndexVersioned<T>,
    private val mirror: suspend () -> FileSystemIndexVersioned<T>,
    initialLatestIndex: Int,
    private val mirrorQueue: SequentialMirrorQueue,
) : MutableIndexVersioned<T>, CachedIndexVersioned<T> {
    private val nonce = MutableStateFlow(Random.nextLong())
    private val tail = MutableStateFlow(initialLatestIndex)
    override val cacheNonce: StateFlow<Long> = nonce.asStateFlow()
    override val latestIndex: StateFlow<Int> = tail.asStateFlow()
    private fun backing(): MutableIndexVersioned<T> {
        check(owner.isActive) { "Session storage is closed" }
        return source
    }
    override suspend fun latestIndex(): Int = backing().latestIndex()
    override suspend fun get(index: Int): T = backing()[index]
    override suspend fun getExact(index: Int): T? = backing().getExact(index)
    override suspend fun floorToIndex(index: Int): Int? = backing().floorToIndex(index)
    override suspend fun ceilToIndex(index: Int): Int? = backing().ceilToIndex(index)
    override suspend fun indexesIn(range: IntRange): List<Int> = backing().indexesIn(range)
    override suspend fun valuesIn(range: IntRange): List<Pair<Int, T>> = backing().valuesIn(range)
    override suspend fun set(index: Int, value: T) {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            backing()[index] = value
            tail.value = index
            mirrorQueue.enqueue { mirror().setUnsafe(index, value) }
        }
    }
    override suspend fun revert(untilExclusive: Int) {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            val previous = tail.value
            backing().revert(untilExclusive)
            val latest = if (previous < untilExclusive) previous else backing().latestIndex()
            if (latest != previous) {
                nonce.value = Random.nextLong()
                tail.value = latest
            }
            mirrorQueue.enqueue { mirror().revert(untilExclusive) }
        }
    }
}

internal fun FileSystemAgentStorage.timeline(directoryName: String): FileSystemIndexVersioned<*> = when (directoryName) {
    "index" -> index
    "work" -> work
    "settings" -> settings
    "timestamp" -> timestamp
    "token-count" -> tokenCount
    "unstable" -> unstable
    else -> error("Unknown timeline directory: $directoryName")
}

internal fun Path.toStorageUri(): String {
    val path = toString()
    if (
        path.startsWith("""\\""") ||
        (path.length >= 3 && path[1] == ':' && path[2] in charArrayOf('\\', '/'))
    ) {
        val normalized = path.removePrefix("""\\?\""").replace('\\', '/')
        return if (normalized.startsWith("//")) "file:$normalized" else "file:///$normalized"
    }
    return "file://$path"
}

private const val DefaultMirrorQueueCapacity: Int = 4_096
private val DefaultMirrorDrainTimeout: Duration = 5.seconds
