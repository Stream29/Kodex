@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentsession.sqlite

import io.github.stream29.kodex.agentruntime.contract.AgentRuntime
import io.github.stream29.kodex.agentruntime.impl.buildMasterAgentRuntime
import io.github.stream29.kodex.agentsession.contract.*
import io.github.stream29.kodex.agentstate.impl.KodexAgentState
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.SteerSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.encodePayloadJson
import io.github.stream29.kodex.agentstorage.contract.*
import io.github.stream29.kodex.agentstorage.sqlite.*
import io.github.stream29.kodex.utils.coroutines.CoroutineCloseable
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.closeOnFailure
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.sqlite.*
import kotlinx.coroutines.*
import kotlinx.coroutines.cancelAndJoin as cancelJobAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

public actual class SqliteKodexSessionRepository internal constructor(
    scope: CoroutineScope,
    private val root: Path,
    private val dependencies: KodexAgentDependencies,
    private val fileSystem: CoroutineFileSystem,
    private val connection: SqliteConnection,
    private val mutex: Mutex,
    private val ownsConnection: Boolean,
    initialEntries: List<Int>,
    private val sessionTtl: Duration,
    private val origin: TimeMark,
    private val onTurnActiveRenewal: suspend () -> Unit,
    private val turnRenewalInterval: () -> Duration,
    reportMirrorFailure: (Throwable) -> Unit,
) : KodexRootSessionRepository, CoroutineCloseable, CoroutineScope by scope {
    private val lifecycleMutex = Mutex()
    private val openRoots = linkedMapOf<Int, OpenSqliteSessionHandle>()
    private val mirrorScope = CoroutineScope(scope.coroutineContext.minusKey(Job) + SupervisorJob())
    private val mirror = SequentialMirrorQueue(mirrorScope, reportMirrorFailure)
    private val mutableEntries = MutableStateFlow(initialEntries)
    private val mutableOpenSessions = MutableStateFlow<Map<Int, KodexAgentSession>>(emptyMap())
    private val mutableStopEvents = MutableSharedFlow<IndexedAgentStopEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    public actual override val entries: StateFlow<List<Int>> = mutableEntries.asStateFlow()
    public actual override val openSessions: StateFlow<Map<Int, KodexAgentSession>> = mutableOpenSessions.asStateFlow()
    public actual override val stopEvents: Flow<IndexedAgentStopEvent> = mutableStopEvents.asSharedFlow()

    private var closed = false
    private val cleanup = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                lifecycleMutex.withLock {
                    mutex.withLock { closed = true }
                    val handles = openRoots.values.toList()
                    openRoots.clear()
                    mutableOpenSessions.value = emptyMap()
                    for (handle in handles) {
                        drainHandleJobs(handle, currentCoroutineContext()[Job])
                    }
                    try {
                        mirror.closeAndDrain()
                    } finally {
                        mirrorScope.cancel()
                        if (ownsConnection) {
                            withDbLock { connection.close() }
                        }
                    }
                }
            }
        }
    }

    private suspend fun <T> withDbLock(block: () -> T): T =
        mutex.withLock { withContext(Dispatchers.IO) { block() } }

    private fun requireOpen() {
        check(!closed && coroutineContext[Job]?.isActive == true) { "Session repository is closed" }
    }

    private fun inventory(): List<Int> = querySessionInventory(connection)

    private fun requireEntry(index: Int) {
        require(
            index >= 0 &&
                connection.query("SELECT 1 FROM sessions WHERE session_id = ?", listOf(index.toLong())).isNotEmpty(),
        ) { "Session does not exist" }
    }

    private fun directory(index: Int): Path = Path(root, "sessions", index.toString())

    public actual override suspend fun list(): List<Int> = withDbLock {
        requireOpen()
        inventory()
    }

    public actual override suspend fun listEntries(): List<KodexRootSessionEntry> = listEntries(true)

    public actual override suspend fun listEntries(includeArchived: Boolean): List<KodexRootSessionEntry> = withDbLock {
        requireOpen()
        val activeSessions = mutableOpenSessions.value
        connection.query(
            "SELECT * FROM session_catalog " + (if (includeArchived) "" else "WHERE archived = 0 ") + "ORDER BY session_id",
        ).map { row -> entry(row, activeSessions) }
    }

    public actual override suspend fun getEntry(entryIndex: Int): KodexRootSessionEntry = withDbLock {
        requireOpen()
        requireEntry(entryIndex)
        val activeSessions = mutableOpenSessions.value
        entry(
            connection.query("SELECT * FROM session_catalog WHERE session_id = ?", listOf(entryIndex.toLong())).single(),
            activeSessions,
        )
    }

    private fun instant(raw: Any?): Instant? = (raw as String?)?.let(Instant::parse)

    private fun entry(
        row: List<Any?>,
        activeSessions: Map<Int, KodexAgentSession>,
    ): KodexRootSessionEntry {
        val index = (row[0] as Long).toInt()
        val owner = activeSessions[index]?.takeIf { it.coroutineContext[Job]?.isActive == true }
        return object : KodexRootSessionEntry {
            override val entryIndex = index
            override val archived = row[1] == 1L
            override val threadName = row[2] as String?
            override val createdAt = instant(row[4])
            override val lastActivityAt = instant(row[5])
            override val isActive = owner != null
            override val running = owner?.runtime?.runningTurn?.value != null
            override suspend fun archive() = archive(index, true)
            override suspend fun unarchive() = archive(index, false)
        }
    }

    private suspend fun archive(index: Int, value: Boolean): Unit = withDbLock {
        requireOpen()
        requireEntry(index)
        connection.transaction {
            connection.execute("UPDATE sessions SET archived = ? WHERE session_id = ?", listOf(if (value) 1L else 0L, index.toLong()))
        }
        val fs = fileSystem
        val target = directory(index)
        val marker = Path(target, "archive.mark")
        mirror.enqueue {
            fs.createDirectories(target)
            if (value) fs.writeString(marker, "") else fs.delete(marker, mustExist = false)
        }
    }

    public actual override suspend fun create(): Int = createCopy(null)

    public actual override suspend fun createFork(sourceEntryIndex: Int): Int = createCopy(sourceEntryIndex)

    private suspend fun createCopy(source: Int?): Int = withDbLock {
        requireOpen()
        var files = emptyList<Triple<String, Int, String>>()
        val index = connection.transaction {
            if (source != null) requireEntry(source)
            var candidate = 0L
            inventory().forEach { if (it.toLong() == candidate) candidate++ }
            check(candidate <= Int.MAX_VALUE)
            connection.execute("INSERT INTO sessions(session_id) VALUES (?)", listOf(candidate))
            connection.execute(
                "INSERT INTO agents(agent_id, session_id, parent_agent_id) VALUES (?, ?, NULL)",
                listOf(candidate, candidate),
            )
            if (source != null) {
                val emptySteerJson = SteerSnapshot(emptyList()).encodePayloadJson()
                connection.execute(
                    """INSERT INTO agent_commits (agent_id, type, "index", payload_json, created_at) SELECT ?, type, "index", CASE WHEN type = ? THEN ? ELSE payload_json END, created_at FROM agent_commits WHERE agent_id = ?""",
                    listOf(candidate, AgentCommitType.Steer.wireName, emptySteerJson, source.toLong()),
                )
                val snapshot = connection.query(
                    """SELECT type, "index", payload_json FROM agent_commits WHERE agent_id = ? ORDER BY "index", type""",
                    listOf(candidate),
                )
                files = snapshot.map { row ->
                    val type = row[0] as String
                    Triple(mirrorTimelineDirectory(type), (row[1] as Long).toInt(), row[2] as String)
                }
            }
            candidate.toInt()
        }
        val fs = fileSystem
        val target = directory(index)
        val snapshotFiles = files
        mirror.enqueue {
            // An index can be reused after deletion; discard any stale mirror first.
            deleteRecursively(fs, target)
            initializeMirrorDirectory(target, fs, snapshotFiles)
        }
        mutableEntries.value = inventory()
        index
    }

    public actual override suspend fun open(entryIndex: Int): KodexAgentSession = lifecycleMutex.withLock {
        withDbLock {
            requireOpen()
            requireEntry(entryIndex)
        }
        openRoots[entryIndex]?.let { existing ->
            if (existing.session.coroutineContext[Job]?.isActive == true) {
                renewLocked(existing)
                return@withLock existing.session
            }
            withContext(NonCancellable) {
                closeHandleLocked(entryIndex, existing)
            }
        }

        val storageScope = CoroutineScope(coroutineContext.minusKey(Job) + SupervisorJob())
        val sessionScope = supervisorChildScope()
        try {
            val cache = withDbLock {
                requireOpen()
                val targetDirectory = directory(entryIndex)
                val storage = SqliteAgentStorage(
                    connection = connection,
                    agentId = entryIndex,
                    uri = targetDirectory.toStorageUri(),
                    mutex = mutex,
                    requireAccess = { check(storageScope.isActive) { "Session storage is closed" } },
                )
                val initialLatestIndex = (
                    connection.query(
                        """SELECT coalesce(max("index"), -1) FROM agent_commits WHERE agent_id = ?""",
                        listOf(entryIndex.toLong()),
                    ).single().single() as Long
                ).toInt()
                val cached = CachedSqliteAgentStorage(storageScope, storage, initialLatestIndex)
                FileMirrorKodexAgentStorage(cached, targetDirectory, fileSystem, mirror)
            }
            val state = sessionScope.KodexAgentState(
                client = dependencies.client,
                storage = cache,
                contextSettings = dependencies.contextSettings,
                mcpService = dependencies.mcpService,
            )
            val runtime = state.buildMasterAgentRuntime(dependencies)
            sessionScope.launch(start = CoroutineStart.UNDISPATCHED) {
                runtime.stopEvents.collect { stopEvent ->
                    mutableStopEvents.tryEmit(IndexedAgentStopEvent(entryIndex = entryIndex, event = stopEvent))
                }
            }
            val session = SqliteSession(sessionScope, cache, runtime)
            withDbLock { requireOpen() }
            installHandleLocked(entryIndex, session, storageScope).session
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                sessionScope.cancelAndJoin()
                storageScope.cancelAndJoin()
            }
            throw failure
        }
    }

    public actual override suspend fun closeSession(entryIndex: Int): Unit = lifecycleMutex.withLock {
        val handle = openRoots[entryIndex] ?: return@withLock
        withContext(NonCancellable) {
            closeHandleLocked(entryIndex, handle)
        }
    }

    public actual override suspend fun closeAllSessions(): Unit = lifecycleMutex.withLock {
        if (openRoots.isEmpty()) return@withLock
        withContext(NonCancellable) {
            for ((index, handle) in openRoots.toList()) {
                closeHandleLocked(index, handle)
            }
        }
    }

    public actual override suspend fun delete(entryIndex: Int): Unit = lifecycleMutex.withLock {
        withDbLock {
            requireOpen()
            requireEntry(entryIndex)
        }
        val handle = openRoots.remove(entryIndex)?.also {
            publishOpenSessionsLocked()
        }
        if (handle != null) {
            withContext(NonCancellable) {
                drainHandleJobs(handle, currentCoroutineContext()[Job])
            }
        }
        withDbLock {
            requireOpen()
            connection.transaction {
                connection.execute("DELETE FROM sessions WHERE session_id = ?", listOf(entryIndex.toLong()))
            }
            mutableEntries.value = inventory()
            val fs = fileSystem
            val target = directory(entryIndex)
            mirror.enqueue { deleteRecursively(fs, target) }
        }
    }

    public actual override suspend fun close(): Unit = closeAndJoin()

    public actual suspend fun closeAndJoin() {
        withContext(NonCancellable) {
            this@SqliteKodexSessionRepository.coroutineContext[Job]?.cancelJobAndJoin()
            cleanup.join()
        }
    }

    private fun installHandleLocked(
        entryIndex: Int,
        session: SqliteSession,
        storageScope: CoroutineScope,
    ): OpenSqliteSessionHandle {
        val initialDeadline = if (sessionTtl.isFinite()) origin.elapsedNow() + sessionTtl else Duration.INFINITE
        val handle = OpenSqliteSessionHandle(
            session = session,
            storageScope = storageScope,
            deadline = initialDeadline,
        )
        openRoots[entryIndex] = handle
        publishOpenSessionsLocked()

        handle.renewalJob = launch {
            session.runtime.runningTurn.collectLatest { turn ->
                if (turn != null) {
                    while (true) {
                        val renewed = lifecycleMutex.withLock {
                            if (openRoots[entryIndex] !== handle ||
                                session.coroutineContext[Job]?.isActive != true ||
                                session.runtime.runningTurn.value !== turn
                            ) {
                                false
                            } else {
                                renewLocked(handle)
                                true
                            }
                        }
                        if (!renewed) return@collectLatest
                        onTurnActiveRenewal()
                        delay(nextRenewalDelay())
                    }
                }
            }
        }

        handle.expiryJob = if (sessionTtl.isFinite()) {
            launch {
                while (true) {
                    val remaining = lifecycleMutex.withLock {
                        if (openRoots[entryIndex] !== handle) return@launch
                        handle.deadline - origin.elapsedNow()
                    }
                    if (remaining.isPositive()) {
                        delay(remaining)
                    }
                    val expired = lifecycleMutex.withLock {
                        if (openRoots[entryIndex] !== handle) return@launch
                        if (session.runtime.runningTurn.value != null) {
                            renewLocked(handle)
                            false
                        } else if (handle.deadline > origin.elapsedNow()) {
                            false
                        } else {
                            closeHandleLocked(entryIndex, handle)
                            true
                        }
                    }
                    if (expired) return@launch
                }
            }
        } else {
            null
        }

        handle.completionWatcher = launch {
            session.coroutineContext.job.join()
            lifecycleMutex.withLock {
                if (openRoots[entryIndex] === handle) {
                    withContext(NonCancellable) {
                        openRoots.remove(entryIndex)
                        publishOpenSessionsLocked()
                        handle.renewalJob?.cancelJobAndJoin()
                        handle.expiryJob?.cancelJobAndJoin()
                        handle.storageScope.cancelAndJoin()
                    }
                }
            }
        }

        return handle
    }

    private fun renewLocked(handle: OpenSqliteSessionHandle) {
        if (sessionTtl.isFinite()) {
            handle.deadline = maxOf(handle.deadline, origin.elapsedNow() + sessionTtl)
        }
    }

    private fun nextRenewalDelay(): Duration {
        val candidate = minOf(DefaultKodexSessionRenewalInterval, turnRenewalInterval())
        return if (sessionTtl.isFinite()) {
            minOf(candidate, (sessionTtl / 3).coerceAtLeast(1.milliseconds))
        } else {
            candidate.coerceAtLeast(1.milliseconds)
        }
    }

    private suspend fun closeHandleLocked(entryIndex: Int, handle: OpenSqliteSessionHandle) {
        val caller = currentCoroutineContext()[Job]
        if (openRoots[entryIndex] === handle) {
            openRoots.remove(entryIndex)
            publishOpenSessionsLocked()
        }
        drainHandleJobs(handle, caller)
    }

    private suspend fun drainHandleJobs(handle: OpenSqliteSessionHandle, caller: Job?) =
        withContext(NonCancellable) {
            if (handle.completionWatcher !== caller) {
                handle.completionWatcher?.cancelJobAndJoin()
            }
            if (handle.renewalJob !== caller) {
                handle.renewalJob?.cancelJobAndJoin()
            }
            if (handle.expiryJob !== caller) {
                handle.expiryJob?.cancelJobAndJoin()
            }
            handle.session.cancelAndJoin()
            handle.storageScope.cancelAndJoin()
        }

    private fun publishOpenSessionsLocked() {
        mutableOpenSessions.value = openRoots
            .filterValues { it.session.coroutineContext[Job]?.isActive == true }
            .mapValues { it.value.session }
    }
}

private class OpenSqliteSessionHandle(
    val session: SqliteSession,
    val storageScope: CoroutineScope,
    var deadline: Duration,
) {
    var renewalJob: Job? = null
    var expiryJob: Job? = null
    var completionWatcher: Job? = null
}

private class SqliteSession(
    scope: CoroutineScope,
    override val storage: MutableKodexAgentStorage,
    override val runtime: AgentRuntime,
) : KodexAgentSession, CoroutineScope by scope

public actual suspend fun CoroutineScope.SqliteKodexSessionRepository(
    root: Path,
    dependencies: KodexAgentDependencies,
    fileSystem: CoroutineFileSystem,
    valueCacheSize: Int,
    sessionTtl: Duration,
    timeSource: TimeSource,
    onTurnActiveRenewal: suspend () -> Unit,
    turnRenewalInterval: () -> Duration,
    reportMirrorFailure: (Throwable) -> Unit,
): SqliteKodexSessionRepository {
    require(valueCacheSize > 0)
    require(sessionTtl.isPositive()) { "sessionTtl must be positive: $sessionTtl" }
    fileSystem.createDirectories(root)
    val resolvedRoot = fileSystem.resolve(root)
    val dbPath = Path(resolvedRoot, "kodex.db")
    val dbMetadata = fileSystem.metadataOrNull(dbPath)
    val hasExistingDb = dbMetadata != null && dbMetadata.isRegularFile && dbMetadata.size > 0L
    val hasExistingHomeState =
        fileSystem.exists(Path(resolvedRoot, "version.json")) ||
            fileSystem.exists(Path(resolvedRoot, "sessions")) ||
            fileSystem.exists(Path(resolvedRoot, ".migrations"))
    if (!hasExistingDb && hasExistingHomeState) {
        throw IOException("Authoritative SQLite session database is missing for existing Kodex Home: $dbPath")
    }
    return supervisorChildScope().closeOnFailure(CoroutineScope::cancelAndJoin) { scope ->
        withContext(Dispatchers.IO) {
            openSqliteConnection(dbPath).closeOnFailure { connection ->
                if (hasExistingDb && (connection.query("PRAGMA user_version").single().single() as Long) == 0L) {
                    throw IOException("Authoritative SQLite session database is uninitialized: $dbPath")
                }
                ensureSqlitePhaseOneSchema(connection)
                val initialEntries = querySessionInventory(connection)
                currentCoroutineContext().ensureActive()
                scope.coroutineContext.ensureActive()
                SqliteKodexSessionRepository(
                    scope = scope,
                    root = resolvedRoot,
                    dependencies = dependencies,
                    fileSystem = fileSystem,
                    connection = connection,
                    mutex = Mutex(),
                    ownsConnection = true,
                    initialEntries = initialEntries,
                    sessionTtl = sessionTtl,
                    origin = timeSource.markNow(),
                    onTurnActiveRenewal = onTurnActiveRenewal,
                    turnRenewalInterval = turnRenewalInterval,
                    reportMirrorFailure = reportMirrorFailure,
                )
            }
        }
    }
}

public actual suspend fun CoroutineScope.SqliteKodexSessionRepository(
    root: Path,
    connection: SqliteConnection,
    mutex: Mutex,
    dependencies: KodexAgentDependencies,
    fileSystem: CoroutineFileSystem,
    valueCacheSize: Int,
    sessionTtl: Duration,
    timeSource: TimeSource,
    onTurnActiveRenewal: suspend () -> Unit,
    turnRenewalInterval: () -> Duration,
    reportMirrorFailure: (Throwable) -> Unit,
): SqliteKodexSessionRepository {
    require(valueCacheSize > 0)
    require(sessionTtl.isPositive()) { "sessionTtl must be positive: $sessionTtl" }
    fileSystem.createDirectories(root)
    val resolvedRoot = fileSystem.resolve(root)
    return supervisorChildScope().closeOnFailure(CoroutineScope::cancelAndJoin) { scope ->
        val initialEntries = mutex.withLock {
            withContext(Dispatchers.IO) {
                ensureSqlitePhaseOneSchema(connection)
                querySessionInventory(connection)
            }
        }
        currentCoroutineContext().ensureActive()
        scope.coroutineContext.ensureActive()
        SqliteKodexSessionRepository(
            scope = scope,
            root = resolvedRoot,
            dependencies = dependencies,
            fileSystem = fileSystem,
            connection = connection,
            mutex = mutex,
            ownsConnection = false,
            initialEntries = initialEntries,
            sessionTtl = sessionTtl,
            origin = timeSource.markNow(),
            onTurnActiveRenewal = onTurnActiveRenewal,
            turnRenewalInterval = turnRenewalInterval,
            reportMirrorFailure = reportMirrorFailure,
        )
    }
}

private fun querySessionInventory(connection: SqliteConnection): List<Int> =
    connection.query("SELECT session_id FROM sessions ORDER BY session_id").map { (it[0] as Long).toInt() }

private suspend fun deleteRecursively(fileSystem: CoroutineFileSystem, path: Path) {
    val metadata = fileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) fileSystem.list(path).forEach { deleteRecursively(fileSystem, it) }
    fileSystem.delete(path, mustExist = false)
}
