@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentsession.sqlite

import io.github.stream29.kodex.agentsession.contract.*
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant
import io.github.stream29.kodex.agentstorage.sqlite.*
import io.github.stream29.kodex.agentstorage.contract.*
import io.github.stream29.kodex.agentruntime.contract.AgentRuntime
import io.github.stream29.kodex.agentruntime.impl.buildMasterAgentRuntime
import io.github.stream29.kodex.agentstate.impl.KodexAgentState
import io.github.stream29.kodex.utils.coroutines.CoroutineCloseable
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.closeOnFailure
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.sqlite.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException

public actual class SqliteKodexSessionRepository internal constructor(
    scope: CoroutineScope,
    private val root: Path,
    private val dependencies: KodexAgentDependencies,
    private val fileSystem: CoroutineFileSystem,
    private val connection: SqliteConnection,
    initialEntries: List<Int>,
    reportMirrorFailure: (Throwable) -> Unit,
) : KodexRootSessionRepository, CoroutineCloseable, CoroutineScope by scope {
    private val mutex = Mutex()
    private val lifecycleMutex = Mutex()
    private val openRoots = mutableMapOf<Int, KodexAgentSession>()
    private val mirrorScope = CoroutineScope(scope.coroutineContext.minusKey(Job) + SupervisorJob())
    private val mirror = SequentialMirrorQueue(mirrorScope, reportMirrorFailure)
    private val mutableEntries = MutableStateFlow(initialEntries)
    public actual override val entries: StateFlow<List<Int>> = mutableEntries.asStateFlow()
    private var closed = false
    private val cleanup = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() }
        finally {
            withContext(NonCancellable) {
                lifecycleMutex.withLock {
                    mutex.withLock { closed = true }
                    openRoots.values.toList().forEach { it.coroutineContext[Job]?.cancelAndJoin() }
                    openRoots.clear()
                    try { mirror.closeAndDrain() }
                    finally {
                        mirrorScope.cancel()
                        withDbLock { connection.close() }
                    }
                }
            }
        }
    }
    private suspend fun <T> withDbLock(block: () -> T): T = mutex.withLock { withContext(Dispatchers.IO) { block() } }
    private fun requireOpen() { check(!closed && coroutineContext[Job]?.isActive == true) { "Session repository is closed" } }
    private fun inventory(): List<Int> = querySessionInventory(connection)
    private fun requireEntry(index: Int) {
        require(index >= 0 && connection.query("SELECT 1 FROM sessions WHERE session_id = ?", listOf(index.toLong())).isNotEmpty()) { "Session does not exist" }
    }
    private fun directory(index: Int): Path = Path(root, "sessions", index.toString())
    public actual override suspend fun list(): List<Int> = withDbLock { requireOpen(); inventory() }
    public actual override suspend fun listEntries(): List<KodexRootSessionEntry> = listEntries(true)
    public actual override suspend fun listEntries(includeArchived: Boolean): List<KodexRootSessionEntry> = withDbLock {
        requireOpen()
        connection.query("SELECT * FROM session_catalog " + (if (includeArchived) "" else "WHERE archived = 0 ") + "ORDER BY session_id").map(::entry)
    }
    public actual override suspend fun getEntry(entryIndex: Int): KodexRootSessionEntry = withDbLock {
        requireOpen(); requireEntry(entryIndex)
        entry(connection.query("SELECT * FROM session_catalog WHERE session_id = ?", listOf(entryIndex.toLong())).single())
    }
    private fun instant(raw: Any?): Instant? = (raw as String?)?.let(Instant::parse)
    private fun entry(row: List<Any?>): KodexRootSessionEntry {
        val index = (row[0] as Long).toInt()
        val owner = openRoots[entryIndex(row)]?.takeIf { it.coroutineContext[Job]?.isActive == true }
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
    private fun entryIndex(row: List<Any?>): Int = (row[0] as Long).toInt()
    private suspend fun archive(index: Int, value: Boolean): Unit = withDbLock {
        requireOpen(); requireEntry(index)
        connection.transaction { connection.execute("UPDATE sessions SET archived = ? WHERE session_id = ?", listOf(if (value) 1L else 0L, index.toLong())) }
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
            connection.execute("INSERT INTO agents(agent_id, session_id, parent_agent_id) VALUES (?, ?, NULL)",
                listOf(candidate, candidate))
            if (source != null) {
                connection.execute(
                    """INSERT INTO agent_commits (agent_id, type, "index", payload_json, created_at) SELECT ?, type, "index", payload_json, created_at FROM agent_commits WHERE agent_id = ?""",
                    listOf(candidate, source.toLong()),
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
        val existing = withDbLock {
            requireOpen()
            requireEntry(entryIndex)
            openRoots[entryIndex]?.takeIf { it.coroutineContext[Job]?.isActive == true }
        }
        if (existing != null) return@withLock existing
        supervisorChildScope().closeOnFailure(CoroutineScope::cancelAndJoin) { sessionScope ->
            val cache = withDbLock {
                requireOpen()
                val targetDirectory = directory(entryIndex)
                val storage = SqliteAgentStorage(
                    connection = connection,
                    agentId = entryIndex,
                    uri = targetDirectory.toStorageUri(),
                    mutex = mutex,
                    requireAccess = { check(sessionScope.isActive) { "Session storage is closed" } },
                )
                val initialLatestIndex = (
                    connection.query(
                        """SELECT coalesce(max("index"), -1) FROM agent_commits WHERE agent_id = ?""",
                        listOf(entryIndex.toLong()),
                    ).single().single() as Long
                ).toInt()
                val cached = CachedSqliteAgentStorage(sessionScope, storage, initialLatestIndex)
                FileMirrorKodexAgentStorage(cached, targetDirectory, fileSystem, mirror)
            }
            val state = sessionScope.KodexAgentState(client = dependencies.client, storage = cache, contextSettings = dependencies.contextSettings, mcpService = dependencies.mcpService)
            val session = SqliteSession(sessionScope, cache, state.buildMasterAgentRuntime(dependencies))
            withDbLock { requireOpen(); openRoots[entryIndex] = session }
            session
        }
    }
    public actual override suspend fun delete(entryIndex: Int): Unit = lifecycleMutex.withLock {
        val owner = withDbLock { requireOpen(); requireEntry(entryIndex); openRoots[entryIndex] }
        withContext(NonCancellable) { owner?.coroutineContext?.get(Job)?.cancelAndJoin() }
        withDbLock {
            requireOpen()
            connection.transaction { connection.execute("DELETE FROM sessions WHERE session_id = ?", listOf(entryIndex.toLong())) }
            openRoots.remove(entryIndex)
            mutableEntries.value = inventory()
            val fs = fileSystem
            val target = directory(entryIndex)
            mirror.enqueue { deleteRecursively(fs, target) }
        }
    }
    public actual override suspend fun close(): Unit = closeAndJoin()
    public actual suspend fun closeAndJoin() {
        withContext(NonCancellable) {
            this@SqliteKodexSessionRepository.coroutineContext[Job]?.cancelAndJoin()
            cleanup.join()
        }
    }
}

private class SqliteSession(scope: CoroutineScope, override val storage: MutableKodexAgentStorage, override val runtime: AgentRuntime) : KodexAgentSession, CoroutineScope by scope

public actual suspend fun CoroutineScope.SqliteKodexSessionRepository(
    root: Path,
    dependencies: KodexAgentDependencies,
    fileSystem: CoroutineFileSystem,
    valueCacheSize: Int,
    reportMirrorFailure: (Throwable) -> Unit,
): SqliteKodexSessionRepository {
    require(valueCacheSize > 0)
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
                    initialEntries = initialEntries,
                    reportMirrorFailure = reportMirrorFailure,
                )
            }
        }
    }
}

private fun querySessionInventory(connection: SqliteConnection): List<Int> =
    connection.query("SELECT session_id FROM sessions ORDER BY session_id").map { (it[0] as Long).toInt() }

private suspend fun deleteRecursively(fileSystem: CoroutineFileSystem, path: Path) {
    val metadata = fileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) fileSystem.list(path).forEach { deleteRecursively(fileSystem, it) }
    fileSystem.delete(path, mustExist = false)
}
