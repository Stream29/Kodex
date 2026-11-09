package io.github.stream29.kodex.agentsession.sqlite

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.encodePayloadJson
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.ObservableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.filesystemlayout.deleteRecord
import io.github.stream29.kodex.agentstorage.filesystemlayout.latestIndexPath
import io.github.stream29.kodex.agentstorage.filesystemlayout.recordPath
import io.github.stream29.kodex.agentstorage.filesystemlayout.storedRecordIndexes
import io.github.stream29.kodex.agentstorage.filesystemlayout.timelineDirectory
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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
    private val owner: CoroutineScope,
    private val source: MutableKodexAgentStorage,
    initialLatestIndex: Int,
) : MutableKodexAgentStorage, ObservableKodexAgentStorage {
    private val nonce = MutableStateFlow(Random.nextLong())
    private val tail = MutableStateFlow(initialLatestIndex)

    override val uri: String get() = source.uri
    override val cacheNonce: StateFlow<Long> = nonce.asStateFlow()
    override val latestIndexFlow: StateFlow<Int> = tail.asStateFlow()

    private fun backing(): MutableKodexAgentStorage {
        check(owner.isActive) { "Session storage is closed" }
        return source
    }

    override suspend fun latestIndex(): Int = backing().latestIndex()

    override suspend fun countCommitsIn(
        range: IntRange,
        types: Set<AgentCommitType>,
    ): Int = backing().countCommitsIn(range, types)

    override suspend fun getFirstCommitsIn(
        range: IntRange,
        types: Set<AgentCommitType>,
        limit: Int,
    ): List<IndexedAgentCommit> = backing().getFirstCommitsIn(range, types, limit)

    override suspend fun getLastCommitsIn(
        range: IntRange,
        types: Set<AgentCommitType>,
        limit: Int,
    ): List<IndexedAgentCommit> = backing().getLastCommitsIn(range, types, limit)

    @Suppress("DEPRECATION")
    override suspend fun commit(commit: AgentCommit): Int {
        val legacyTimestamp = (commit as? io.github.stream29.kodex.agentstorage.cleanmodels.stable.CommitTimestamp)?.instant
        return commit(listOf(commit), legacyTimestamp ?: Clock.System.now())
    }

    override suspend fun commit(commit: AgentCommit, timestamp: Instant): Int = commit(listOf(commit), timestamp)

    @Suppress("DEPRECATION")
    override suspend fun commit(commits: List<AgentCommit>): Int {
        val legacyTimestamp = (commits.firstOrNull { it is io.github.stream29.kodex.agentstorage.cleanmodels.stable.CommitTimestamp } as? io.github.stream29.kodex.agentstorage.cleanmodels.stable.CommitTimestamp)?.instant
        return commit(commits, legacyTimestamp ?: Clock.System.now())
    }

    override suspend fun commit(commits: List<AgentCommit>, timestamp: Instant): Int {
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            val index = backing().commit(commits, timestamp)
            tail.value = index
            index
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
        }
    }
}

internal class FileMirrorKodexAgentStorage(
    private val delegate: CachedSqliteAgentStorage,
    private val directory: Path,
    private val fileSystem: CoroutineFileSystem,
    private val mirrorQueue: SequentialMirrorQueue,
) : MutableKodexAgentStorage, ObservableKodexAgentStorage by delegate {
    @Suppress("DEPRECATION")
    override suspend fun commit(commit: AgentCommit): Int {
        val legacyTimestamp = (commit as? io.github.stream29.kodex.agentstorage.cleanmodels.stable.CommitTimestamp)?.instant
        return commit(listOf(commit), legacyTimestamp ?: Clock.System.now())
    }

    override suspend fun commit(commit: AgentCommit, timestamp: Instant): Int = commit(listOf(commit), timestamp)

    @Suppress("DEPRECATION")
    override suspend fun commit(commits: List<AgentCommit>): Int {
        val legacyTimestamp = (commits.firstOrNull { it is io.github.stream29.kodex.agentstorage.cleanmodels.stable.CommitTimestamp } as? io.github.stream29.kodex.agentstorage.cleanmodels.stable.CommitTimestamp)?.instant
        return commit(commits, legacyTimestamp ?: Clock.System.now())
    }

    override suspend fun commit(commits: List<AgentCommit>, timestamp: Instant): Int {
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            val index = delegate.commit(commits, timestamp)
            val encoded = commits.map { commit ->
                commit.commitType.legacyTimelineName to commit.encodePayloadJson()
            }
            mirrorQueue.enqueue {
                for ((timelineName, payloadJson) in encoded) {
                    writeMirrorRecordAtomically(directory, timelineName, index, payloadJson, fileSystem)
                }
            }
            index
        }
    }

    override suspend fun revert(untilExclusive: Int) {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            delegate.revert(untilExclusive)
            mirrorQueue.enqueue {
                revertMirrorDirectory(directory, untilExclusive, fileSystem)
            }
        }
    }
}

internal val MirrorTimelineDirectories: List<String> = listOf(
    "index",
    "work",
    "settings",
    "timestamp",
    "token-count",
    "llm-request-meta",
    "unstable",
)

internal fun mirrorTimelineDirectory(type: String): String =
    AgentCommitType.fromWireName(type).legacyTimelineName

internal suspend fun initializeMirrorDirectory(
    directory: Path,
    fileSystem: CoroutineFileSystem,
    initialRecords: List<Triple<String, Int, String>> = emptyList(),
) {
    fileSystem.createDirectories(directory, mustCreate = true)
    val resolved = fileSystem.resolve(directory)
    for (timeline in MirrorTimelineDirectories) {
        val timelineDir = timelineDirectory(resolved, timeline)
        fileSystem.createDirectories(timelineDir, mustCreate = true)
        val rows = initialRecords.filter { it.first == timeline }
        rows.forEach { (_, index, payloadJson) ->
            fileSystem.writeString(recordPath(timelineDir, index), payloadJson)
        }
        val latest = rows.maxOfOrNull { it.second } ?: -1
        writeMirrorLatestIndexAtomically(timelineDir, latest, fileSystem)
    }
}

internal suspend fun writeMirrorRecordAtomically(
    directory: Path,
    timelineName: String,
    index: Int,
    payloadJson: String,
    fileSystem: CoroutineFileSystem,
) {
    fileSystem.createDirectories(directory)
    val timelineDir = timelineDirectory(directory, timelineName)
    fileSystem.createDirectories(timelineDir)
    writeAtomically(timelineDir, recordPath(timelineDir, index), payloadJson, fileSystem)
    writeMirrorLatestIndexAtomically(timelineDir, index, fileSystem)
}

internal suspend fun revertMirrorDirectory(
    directory: Path,
    untilExclusive: Int,
    fileSystem: CoroutineFileSystem,
) {
    for (timeline in MirrorTimelineDirectories) {
        val timelineDir = timelineDirectory(directory, timeline)
        if (fileSystem.metadataOrNull(timelineDir)?.isDirectory != true) continue
        val indexes = storedRecordIndexes(timelineDir, fileSystem)
        val suffix = indexes.filter { it >= untilExclusive }
        if (suffix.isEmpty()) continue
        for (idx in suffix.asReversed()) {
            deleteRecord(timelineDir, idx, fileSystem, mustExist = false)
        }
        val revertedLatest = indexes.lastOrNull { it < untilExclusive } ?: -1
        writeMirrorLatestIndexAtomically(timelineDir, revertedLatest, fileSystem)
    }
}

private suspend fun writeMirrorLatestIndexAtomically(
    timelineDir: Path,
    index: Int,
    fileSystem: CoroutineFileSystem,
) {
    writeAtomically(timelineDir, latestIndexPath(timelineDir), index.toString(), fileSystem)
}

@OptIn(ExperimentalUuidApi::class)
private suspend fun writeAtomically(
    directory: Path,
    destination: Path,
    content: String,
    fileSystem: CoroutineFileSystem,
) {
    val temporary = Path(directory, ".kodex-write-${Uuid.generateV7()}.tmp")
    var primaryFailure: Throwable? = null
    try {
        fileSystem.writeString(temporary, content, mustCreate = true)
        fileSystem.atomicMove(temporary, destination)
    } catch (failure: Throwable) {
        primaryFailure = failure
        throw failure
    } finally {
        val cleanup = withContext(NonCancellable) {
            runCatching { fileSystem.delete(temporary, mustExist = false) }
        }
        cleanup.onFailure { cleanupFailure ->
            val failure = primaryFailure ?: throw cleanupFailure
            if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
        }
    }
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
