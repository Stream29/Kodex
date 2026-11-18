@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentsession.sqlite

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.agentruntime.contract.AgentRuntime
import io.github.stream29.kodex.agentruntime.contract.AgentResumeNotAdmittedException
import io.github.stream29.kodex.agentruntime.contract.PendingAwaitDeadline
import io.github.stream29.kodex.agentruntime.impl.buildMasterAgentRuntime
import io.github.stream29.kodex.agentsession.contract.*
import io.github.stream29.kodex.agentstate.impl.KodexAgentState
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.SteerSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.UnstableSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.decodePayloadJson
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.encodePayloadJson
import io.github.stream29.kodex.agentstorage.contract.MutableObservableKodexAgentStorage
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

public actual class SqliteKodexSessionRepository internal constructor(
    scope: CoroutineScope,
    private val root: Path,
    private val dependencies: KodexAgentDependencies,
    private val fileSystem: CoroutineFileSystem,
    private val connection: SqliteConnection,
    private val agents: SqliteAgentRecords,
    private val mutex: Mutex,
    private val ownsConnection: Boolean,
    initialEntries: List<Int>,
    initialDeadlines: Map<Int, PendingAwaitDeadline>,
    sessionTtl: Duration,
    origin: TimeMark,
    onTurnActiveRenewal: suspend () -> Unit,
    turnRenewalInterval: () -> Duration,
    reportMirrorFailure: (Throwable) -> Unit,
    private val onDeleteInTransaction: (sessionId: Int) -> Unit = {},
) : KodexSessionRepository, CoroutineCloseable, CoroutineScope by scope {
    private val lifecycleMutex = Mutex()
    private val residency = SessionResidencyTable(
        scope = this,
        mutex = lifecycleMutex,
        sessionTtl = sessionTtl,
        origin = origin,
        onTurnActiveRenewal = onTurnActiveRenewal,
        turnRenewalInterval = turnRenewalInterval,
    )
    private val mirrorScope = CoroutineScope(scope.coroutineContext.minusKey(Job) + SupervisorJob())
    private val mirror = SequentialMirrorQueue(mirrorScope, reportMirrorFailure)
    private val mutableEntries = MutableStateFlow(initialEntries)
    private val mutableAwaitDeadlines = MutableStateFlow(initialDeadlines)
    private val mutableStopEvents = MutableSharedFlow<IndexedAgentStopEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    public actual override val entries: StateFlow<List<Int>> = mutableEntries.asStateFlow()
    public actual override val openSessions: StateFlow<Map<Int, KodexAgentSession>> = residency.openSessions
    public actual override val pendingAwaitDeadlines: StateFlow<Map<Int, PendingAwaitDeadline>> = mutableAwaitDeadlines.asStateFlow()
    public actual override val stopEvents: Flow<IndexedAgentStopEvent> = mutableStopEvents.asSharedFlow()

    private var closed = false
    private val cleanup = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                lifecycleMutex.withLock {
                    mutex.withLock { closed = true }
                    val handles = residency.detachAllLocked()
                    for (handle in handles) {
                        residency.drainDetached(handle)
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

    private fun childDirectory(sessionId: Int, agentId: Int): Path =
        Path(directory(sessionId), "agents", agentId.toString())

    public actual override suspend fun getRootAgentId(entryIndex: Int): Int = withDbLock {
        requireOpen()
        agents.rootId(entryIndex)
    }

    public actual override suspend fun getAgentRecord(agentId: Int): StoredAgent? = withDbLock {
        requireOpen()
        agents.get(agentId)
    }

    public actual suspend fun createChild(parentAgentId: Int): StoredAgent = lifecycleMutex.withLock {
        withDbLock {
            requireOpen()
            val child = connection.transaction { agents.createChild(parentAgentId) }
            val target = childDirectory(child.sessionId, child.agentId)
            mirror.enqueue { initializeMirrorDirectory(target, fileSystem, emptyList()) }
            child
        }
    }

    public actual suspend fun discardUninitializedChild(agentId: Int): Boolean = lifecycleMutex.withLock {
        withDbLock {
            requireOpen()
            val sessionId = connection.transaction { agents.discardUninitializedChild(agentId) }
                ?: return@withDbLock false
            val target = childDirectory(sessionId, agentId)
            mirror.enqueue { deleteRecursively(fileSystem, target) }
            true
        }
    }

    public actual suspend fun markChildCompleted(agentId: Int): Int? = withDbLock {
        requireOpen()
        connection.transaction { agents.markChildCompleted(agentId) }
    }

    public actual override suspend fun list(): List<Int> = withDbLock {
        requireOpen()
        inventory()
    }

    public actual override suspend fun listEntries(includeArchived: Boolean): List<KodexSessionEntry> = withDbLock {
        requireOpen()
        val activeSessions = openSessions.value
        connection.query(
            "$RootCatalog " + (if (includeArchived) "" else "WHERE c.archived = 0 ") + "ORDER BY c.session_id",
        ).map { row -> entry(row, activeSessions) }
    }

    public actual override suspend fun getEntry(entryIndex: Int): KodexSessionEntry = withDbLock {
        requireOpen()
        requireEntry(entryIndex)
        val activeSessions = openSessions.value
        entry(
            connection.query("$RootCatalog WHERE c.session_id = ?", listOf(entryIndex.toLong())).single(),
            activeSessions,
        )
    }

    private fun instant(raw: Any?): Instant? = (raw as String?)?.let(Instant::parse)

    private fun entry(
        row: List<Any?>,
        activeSessions: Map<Int, KodexAgentSession>,
    ): KodexSessionEntry {
        val index = (row[0] as Long).toInt()
        val rootId = checkNotNull(row[6] as Long?) { "Session $index is missing its root Agent." }.toInt()
        val owner = activeSessions[rootId]?.takeIf { it.coroutineContext[Job]?.isActive == true }
        return object : KodexSessionEntry {
            override val entryIndex = index
            override val rootAgentId = rootId
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
        val (index, targetAgent) = connection.transaction {
            val sourceAgent = source?.let(agents::rootId)
            var candidate = 0L
            inventory().forEach { if (it.toLong() == candidate) candidate++ }
            check(candidate <= Int.MAX_VALUE)
            connection.execute("INSERT INTO sessions(session_id) VALUES (?)", listOf(candidate))
            val targetAgent = agents.createRoot(candidate.toInt())
            if (sourceAgent != null) {
                val emptySteerJson = SteerSnapshot(emptyList()).encodePayloadJson()
                // A fork must not import the source Agent's armed wait.
                connection.execute(
                    """INSERT INTO agent_commits (agent_id, type, "index", payload_json, created_at)
                        SELECT ?, type, "index", CASE
                            WHEN type = ? THEN ?
                            WHEN type = 'unstable' AND instr(payload_json, '"armed_for_agent_id"') > 0 THEN (
                                SELECT json_group_array(
                                    json(CASE WHEN json_extract(item.value, '$.type') = 'await_event'
                                        THEN json_remove(item.value, '$.armed_for_agent_id') ELSE item.value END)
                                    ORDER BY item.key
                                ) FROM json_each(payload_json) AS item
                            )
                            ELSE payload_json
                        END, created_at FROM agent_commits WHERE agent_id = ?""",
                    listOf(targetAgent.toLong(), AgentCommitType.Steer.wireName, emptySteerJson, sourceAgent.toLong()),
                )
                val snapshot = connection.query(
                    """SELECT type, "index", payload_json FROM agent_commits WHERE agent_id = ? ORDER BY "index", type""",
                    listOf(targetAgent.toLong()),
                )
                files = snapshot.map { row ->
                    val type = row[0] as String
                    Triple(mirrorTimelineDirectory(type), (row[1] as Long).toInt(), row[2] as String)
                }
            }
            candidate.toInt() to targetAgent
        }
        val fs = fileSystem
        val target = directory(index)
        val snapshotFiles = files
        if (source != null) refreshDeadlineLocked(targetAgent)
        mirror.enqueue {
            // An index can be reused after deletion; discard any stale mirror first.
            deleteRecursively(fs, target)
            initializeMirrorDirectory(target, fs, snapshotFiles)
        }
        mutableEntries.value = inventory()
        index
    }

    public actual override suspend fun open(entryIndex: Int): KodexAgentSession = lifecycleMutex.withLock {
        val record = withDbLock {
            requireOpen()
            checkNotNull(agents.get(agents.rootId(entryIndex)))
        }
        openLocked(record)
    }

    public actual override suspend fun openAgent(agentId: Int): KodexAgentSession? = lifecycleMutex.withLock {
        val record = withDbLock {
            requireOpen()
            agents.get(agentId)
        } ?: return@withLock null
        openLocked(record)
    }

    public actual suspend fun openForAwaitDeadline(agentId: Int, expected: PendingAwaitDeadline): KodexAgentSession? =
        lifecycleMutex.withLock {
            if (mutableAwaitDeadlines.value[agentId] != expected) return@withLock null
            val record = withDbLock {
                requireOpen()
                if (mutableAwaitDeadlines.value[agentId] != expected) return@withDbLock null
                agents.get(agentId)
            } ?: return@withLock null
            openLocked(record)
        }

    private suspend fun openLocked(record: StoredAgent): KodexAgentSession {
        val agentId = record.agentId
        residency.reuseOrCleanupLocked(agentId)?.let { return it }

        val storageScope = CoroutineScope(coroutineContext.minusKey(Job) + SupervisorJob())
        val sessionScope = supervisorChildScope()
        return try {
            val cache = withDbLock {
                requireOpen()
                val targetDirectory = if (record.parentAgentId == null) directory(record.sessionId)
                else childDirectory(record.sessionId, agentId)
                val storage = SqliteAgentStorage(
                    connection = connection,
                    agentId = agentId,
                    uri = targetDirectory.toStorageUri(),
                    mutex = mutex,
                    requireAccess = { check(storageScope.isActive) { "Session storage is closed" } },
                    onHistoryChanged = { changedTypes ->
                        if (changedTypes == null || AgentCommitType.Unstable in changedTypes) {
                            refreshDeadlineLocked(agentId)
                        }
                    },
                )
                val initialLatestIndex = (
                    connection.query(
                        """SELECT coalesce(max("index"), -1) FROM agent_commits WHERE agent_id = ?""",
                        listOf(agentId.toLong()),
                    ).single().single() as Long
                ).toInt()
                val observable = ScopedObservableAgentStorage(storageScope, storage, initialLatestIndex)
                FileMirrorKodexAgentStorage(storage, observable, targetDirectory, fileSystem, mirror)
            }
            val state = sessionScope.KodexAgentState(
                client = dependencies.client,
                storage = cache,
                contextSettings = dependencies.contextSettings,
                clock = dependencies.clock,
            )
            val runtime = state.buildMasterAgentRuntime(dependencies) { claim ->
                lifecycleMutex.withLock {
                    val owner = residency.activeSessionOrNullLocked(agentId)
                    if (owner?.coroutineContext?.get(Job) !== sessionScope.coroutineContext[Job]) {
                        throw AgentResumeNotAdmittedException()
                    }
                    claim()
                }
            }
            sessionScope.launch(start = CoroutineStart.UNDISPATCHED) {
                runtime.stopEvents.collect { stopEvent ->
                    mutableStopEvents.tryEmit(IndexedAgentStopEvent(record.sessionId, stopEvent, agentId))
                }
            }
            val session = SqliteSession(sessionScope, record.sessionId, cache, runtime)
            withDbLock { requireOpen() }
            residency.installLocked(agentId, session, storageScope)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                sessionScope.cancelAndJoin()
                storageScope.cancelAndJoin()
            }
            throw failure
        }
    }

    public actual override suspend fun closeSession(entryIndex: Int): Unit = lifecycleMutex.withLock {
        val rootId = withDbLock { agents.findRootId(entryIndex) } ?: return@withLock
        residency.closeLocked(rootId)
    }

    public actual override suspend fun closeAllSessions(): Unit = lifecycleMutex.withLock {
        residency.closeAllLocked()
    }

    public actual override suspend fun delete(entryIndex: Int): Unit = lifecycleMutex.withLock {
        val (rootId, agentIds) = withDbLock {
            requireOpen()
            agents.rootId(entryIndex) to agents.idsInSession(entryIndex)
        }
        if (agentIds.any { id -> id != rootId && residency.activeSessionOrNullLocked(id)?.runtime?.runningTurn?.value != null }) {
            throw RunningSubagentsPreventDeletion()
        }
        val handles = agentIds.mapNotNull(residency::detachLocked)
        withContext(NonCancellable) {
            handles.forEach { residency.drainDetached(it) }
            withDbLock {
                requireOpen()
                connection.transaction {
                    onDeleteInTransaction(entryIndex)
                    connection.execute("DELETE FROM sessions WHERE session_id = ?", listOf(entryIndex.toLong()))
                }
                mutableAwaitDeadlines.value -= agentIds.toSet()
                mutableEntries.value = inventory()
                val fs = fileSystem
                val target = directory(entryIndex)
                mirror.enqueue { deleteRecursively(fs, target) }
            }
        }
    }

    public actual override suspend fun close(): Unit = closeAndJoin()

    /** Called under the same DB lock that serialized the write, after it committed. */
    private fun refreshDeadlineLocked(agentId: Int) {
        val deadline = try {
            connection.query(
                """SELECT "index", payload_json FROM agent_commits
                    WHERE agent_id = ? AND type = 'unstable' ORDER BY "index" DESC LIMIT 1""",
                listOf(agentId.toLong()),
            ).singleOrNull()?.let { row ->
                decodeAwaitDeadline(agentId, (row[0] as Long).toInt(), row[1] as String)
            }
        } catch (error: Exception) {
            AwaitProjectionLogger.error(error) { "Cannot refresh await_event deadline for Agent $agentId." }
            return
        }
        mutableAwaitDeadlines.value = if (deadline == null) mutableAwaitDeadlines.value - agentId
        else mutableAwaitDeadlines.value + (agentId to deadline)
    }

    public actual suspend fun closeAndJoin() {
        withContext(NonCancellable) {
            this@SqliteKodexSessionRepository.coroutineContext[Job]?.cancelJobAndJoin()
            cleanup.join()
        }
    }
}

private class SqliteSession(
    scope: CoroutineScope,
    override val sessionId: Int,
    override val storage: MutableObservableKodexAgentStorage,
    override val runtime: AgentRuntime,
) : KodexAgentSession, CoroutineScope by scope

public actual suspend fun openVerifiedKodexDatabase(
    root: Path,
    fileSystem: CoroutineFileSystem,
): Pair<Path, SqliteConnection> {
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
    val connection = withContext(Dispatchers.IO) {
        openSqliteConnection(dbPath).closeOnFailure { conn ->
            if (hasExistingDb && (conn.query("PRAGMA user_version").single().single() as Long) == 0L) {
                throw IOException("Authoritative SQLite session database is uninitialized: $dbPath")
            }
            conn
        }
    }
    return resolvedRoot to connection
}

public actual suspend fun CoroutineScope.SqliteKodexSessionRepository(
    root: Path,
    dependencies: KodexAgentDependencies,
    fileSystem: CoroutineFileSystem,
    sessionTtl: Duration,
    timeSource: TimeSource,
    onTurnActiveRenewal: suspend () -> Unit,
    turnRenewalInterval: () -> Duration,
    reportMirrorFailure: (Throwable) -> Unit,
): SqliteKodexSessionRepository {
    require(sessionTtl.isPositive()) { "sessionTtl must be positive: $sessionTtl" }
    val (resolvedRoot, openedConnection) = openVerifiedKodexDatabase(root, fileSystem)
    return openedConnection.closeOnFailure { connection ->
        supervisorChildScope().closeOnFailure(CoroutineScope::cancelAndJoin) { scope ->
            val (initialEntries, initialDeadlines, agents) = withContext(Dispatchers.IO) {
                ensureSqlitePhaseOneSchema(connection)
                Triple(querySessionInventory(connection), queryAwaitDeadlines(connection), SqliteAgentRecords(connection))
            }
            currentCoroutineContext().ensureActive()
            scope.coroutineContext.ensureActive()
            SqliteKodexSessionRepository(
                scope = scope,
                root = resolvedRoot,
                dependencies = dependencies,
                fileSystem = fileSystem,
                connection = connection,
                agents = agents,
                mutex = Mutex(),
                ownsConnection = true,
                initialEntries = initialEntries,
                initialDeadlines = initialDeadlines,
                sessionTtl = sessionTtl,
                origin = timeSource.markNow(),
                onTurnActiveRenewal = onTurnActiveRenewal,
                turnRenewalInterval = turnRenewalInterval,
                reportMirrorFailure = reportMirrorFailure,
            )
        }
    }
}

public actual suspend fun CoroutineScope.SqliteKodexSessionRepository(
    root: Path,
    connection: SqliteConnection,
    mutex: Mutex,
    dependencies: KodexAgentDependencies,
    fileSystem: CoroutineFileSystem,
    sessionTtl: Duration,
    timeSource: TimeSource,
    onTurnActiveRenewal: suspend () -> Unit,
    turnRenewalInterval: () -> Duration,
    reportMirrorFailure: (Throwable) -> Unit,
    onDeleteInTransaction: (sessionId: Int) -> Unit,
): SqliteKodexSessionRepository {
    require(sessionTtl.isPositive()) { "sessionTtl must be positive: $sessionTtl" }
    fileSystem.createDirectories(root)
    val resolvedRoot = fileSystem.resolve(root)
    return supervisorChildScope().closeOnFailure(CoroutineScope::cancelAndJoin) { scope ->
        val (initialEntries, initialDeadlines, agents) = mutex.withLock {
            withContext(Dispatchers.IO) {
                ensureSqlitePhaseOneSchema(connection)
                Triple(querySessionInventory(connection), queryAwaitDeadlines(connection), SqliteAgentRecords(connection))
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
            agents = agents,
            mutex = mutex,
            ownsConnection = false,
            initialEntries = initialEntries,
            initialDeadlines = initialDeadlines,
            sessionTtl = sessionTtl,
            origin = timeSource.markNow(),
            onTurnActiveRenewal = onTurnActiveRenewal,
            turnRenewalInterval = turnRenewalInterval,
            reportMirrorFailure = reportMirrorFailure,
            onDeleteInTransaction = onDeleteInTransaction,
        )
    }
}

private const val RootCatalog = """
    SELECT c.*, root.agent_id FROM session_catalog AS c
    LEFT JOIN agents AS root ON root.session_id = c.session_id AND root.parent_agent_id IS NULL
"""

private fun querySessionInventory(connection: SqliteConnection): List<Int> =
    connection.query("SELECT session_id FROM sessions ORDER BY session_id").map { (it[0] as Long).toInt() }

/** Keyset pages through Agents and reads at most one unstable row per Agent via the primary key. */
private fun queryAwaitDeadlines(connection: SqliteConnection): Map<Int, PendingAwaitDeadline> = buildMap {
    var after = -1L
    while (true) {
        val rows = connection.query(
            """SELECT a.agent_id, c."index", c.payload_json
                FROM (SELECT agent_id FROM agents WHERE agent_id > ? ORDER BY agent_id LIMIT 256) AS a
                LEFT JOIN agent_commits AS c ON c.agent_id = a.agent_id AND c.type = 'unstable'
                    AND c."index" = (SELECT u."index" FROM agent_commits AS u
                        WHERE u.agent_id = a.agent_id AND u.type = 'unstable' ORDER BY u."index" DESC LIMIT 1)
                ORDER BY a.agent_id""",
            listOf(after),
        )
        for ((rawAgentId, rawIndex, payload) in rows) {
            val agentId = (rawAgentId as Long).toInt()
            if (rawIndex is Long && payload is String) {
                decodeAwaitDeadline(agentId, rawIndex.toInt(), payload)?.let { put(agentId, it) }
            }
        }
        if (rows.size < 256) break
        after = rows.last()[0] as Long
    }
}

private fun decodeAwaitDeadline(agentId: Int, index: Int, payload: String): PendingAwaitDeadline? =
    try {
        (AgentCommitType.Unstable.decodePayloadJson(payload) as UnstableSnapshot).pendingDeadline(agentId, index)
    } catch (error: IllegalArgumentException) {
        AwaitProjectionLogger.warn(error) { "Invalid current unstable snapshot for Agent $agentId at index $index." }
        null
    }

private val AwaitProjectionLogger = KotlinLogging.logger {}

private suspend fun deleteRecursively(fileSystem: CoroutineFileSystem, path: Path) {
    val metadata = fileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) fileSystem.list(path).forEach { deleteRecursively(fileSystem, it) }
    fileSystem.delete(path, mustExist = false)
}
