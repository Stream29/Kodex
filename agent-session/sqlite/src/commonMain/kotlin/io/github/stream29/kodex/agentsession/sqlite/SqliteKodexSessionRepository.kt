@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentsession.sqlite

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.agenteventbus.AgentNotificationSignal
import io.github.stream29.kodex.agenteventbus.BackendMqExchanges
import io.github.stream29.kodex.agenteventbus.BackendSignalKeys
import io.github.stream29.kodex.agenteventbus.SignalDelivery
import io.github.stream29.kodex.agenteventbus.publishAfterCommit
import io.github.stream29.kodex.agenteventbus.publishNotification
import io.github.stream29.kodex.agenteventbus.subscribeSignals
import io.github.stream29.kodex.agentruntime.contract.AgentRuntime
import io.github.stream29.kodex.agentruntime.contract.AgentResumeNotAdmittedException
import io.github.stream29.kodex.agentruntime.contract.AgentStopEvent
import io.github.stream29.kodex.agentruntime.contract.PendingAwaitDeadline
import io.github.stream29.kodex.agentruntime.impl.buildMasterAgentRuntime
import io.github.stream29.kodex.agentsession.contract.*
import io.github.stream29.kodex.agentstate.impl.KodexAgentState
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.SteerSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.UnstableSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.decodePayloadJson
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.encodePayloadJson
import io.github.stream29.kodex.agentstorage.sqlite.*
import io.github.stream29.kodex.utils.coroutines.CoroutineCloseable
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.closeOnFailure
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.mq.DefaultMqTopicBufferCapacity
import io.github.stream29.kodex.utils.mq.MqBroker
import io.github.stream29.kodex.utils.mq.MqHeaders
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

public actual class SqliteKodexSessionRepository internal constructor(
    scope: CoroutineScope,
    private val root: Path,
    private val dependencies: KodexAgentDependencies,
    private val fileSystem: CoroutineFileSystem,
    private val connection: SqliteConnection,
    private val mutex: Mutex,
    private val dispatcher: CoroutineDispatcher,
    private val ownedDispatcher: CloseableCoroutineDispatcher? = null,
    initialInventory: RepositoryInventory,
    sessionTtl: Duration,
    origin: TimeMark,
    onTurnActiveRenewal: suspend () -> Unit,
    turnRenewalInterval: () -> Duration,
    private val onDeleteInTransaction: (sessionId: Int) -> () -> Unit = { {} },
    private val broker: MqBroker,
) : KodexSessionRepository, CoroutineCloseable, CoroutineScope by scope {
    private val agents = SqliteAgentRecords(connection)
    private val originId = Uuid.random().toString()
    private val originHeaders = mapOf(MqHeaders.OriginId to originId)
    private val lifecycleMutex = Mutex()
    private val residency = SessionResidencyTable(
        scope = this,
        mutex = lifecycleMutex,
        sessionTtl = sessionTtl,
        origin = origin,
        onTurnActiveRenewal = onTurnActiveRenewal,
        turnRenewalInterval = turnRenewalInterval,
    )
    private val mutableEntries = MutableStateFlow(initialInventory.entries)
    private val mutableAwaitDeadlines = MutableStateFlow(initialInventory.deadlines)
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
    private val groupNonces = SessionGroupNonces(initialInventory.groupNonces)
    public actual val sessionGroupsNonce: Flow<Long> = groupNonces.groups

    @OptIn(ExperimentalCoroutinesApi::class)
    private val runningGroupObservation = scope.launch {
        var previouslyRunning = emptySet<Int>()
        openSessions.flatMapLatest { sessions ->
            if (sessions.isEmpty()) flowOf(emptySet())
            else combine(sessions.map { (id, session) ->
                session.runtime.runningTurn.map { if (it != null) id else null }.distinctUntilChanged()
            }) { running -> running.filterNotNull().toSet() }
        }.distinctUntilChanged().collect { observedRunning ->
            // Sample the authoritative handles and directory after acquiring the same lock
            // as cwd changes and deletes. A scheduled old wake-up cannot reinsert an old group.
            withDbLock {
                if (closed) return@withDbLock
                groupNonces.runningChanged(runningGroupsLocked())
                val changed = (observedRunning - previouslyRunning) + (previouslyRunning - observedRunning)
                previouslyRunning = observedRunning
                changed.forEach { tryPublishSignal(BackendSignalKeys.agentRunning(it)) }
            }
        }
    }
    private val signalObservation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        broker.subscribeSignals(RepositoryBindings, DefaultMqTopicBufferCapacity).collect { delivery ->
            when (delivery) {
                is SignalDelivery.Signal -> if (delivery.headers[MqHeaders.OriginId] != originId) {
                    handleExternalSignal(delivery.routingKey)
                }
                is SignalDelivery.ResyncRequired -> resync()
            }
        }
    }
    private val cleanupResult = CompletableDeferred<Result<Unit>>()
    private val cleanup = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            val result = runCatching {
                withContext(NonCancellable) {
                    if (ownedDispatcher == null) {
                        drainRepository()
                    } else {
                        ownedDispatcher.use {
                            connection.closeOnFailure({ withDbLock { it.close() } }) {
                                drainRepository()
                            }
                            withDbLock { connection.close() }
                        }
                    }
                }
            }
            cleanupResult.complete(result)
            result.getOrThrow()
        }
    }

    private suspend fun drainRepository() = lifecycleMutex.withLock {
        mutex.withLock { closed = true }
        for (handle in residency.detachAllLocked()) {
            residency.drainDetached(handle)
        }
    }

    private suspend fun <T> withDbLock(block: () -> T): T =
        mutex.withLock { withContext(dispatcher) { block() } }

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

    public actual override suspend fun getRootAgentId(entryIndex: Int): Int = withDbLock {
        requireOpen()
        agents.rootId(entryIndex)
    }

    public actual override suspend fun getAgentRecord(agentId: Int): StoredAgent? = withDbLock {
        requireOpen()
        agents.get(agentId)
    }

    public actual suspend fun createChild(parentAgentId: Int, expectedOwner: KodexAgentSession?): StoredAgent = lifecycleMutex.withLock {
        if (expectedOwner != null && residency.activeSessionOrNullLocked(parentAgentId) !== expectedOwner) {
            throw AgentResumeNotAdmittedException()
        }
        withDbLock {
            requireOpen()
            broker.publishAfterCommit(originHeaders) { signals ->
                connection.transaction {
                    agents.createChild(parentAgentId).also { signals.add(BackendSignalKeys.agentTimeline(it.agentId)) }
                }
            }
        }
    }

    public actual suspend fun discardUninitializedChild(agentId: Int): Boolean = lifecycleMutex.withLock {
        val discarded = withDbLock {
            requireOpen()
            val discarded = broker.publishAfterCommit(originHeaders) { signals ->
                connection.transaction {
                    (agents.discardUninitializedChild(agentId) != null).also {
                        if (it) signals.add(BackendSignalKeys.agentDeleted(agentId))
                    }
                }
            }
            if (discarded) mutableAwaitDeadlines.value -= agentId
            discarded
        }
        if (discarded) residency.closeLocked(agentId)
        discarded
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
            rootCatalogQuery(includeArchived),
        ).map { row -> entry(row, activeSessions) }
    }

    public actual fun sessionGroupNonce(cwd: String?): Flow<Long> = groupNonces.group(cwd)

    public actual suspend fun listSessionGroups(): List<StoredSessionGroup> = withDbLock {
        requireOpen()
        val running = runningGroupsLocked().values.toSet()
        connection.readSessionGroupHeads().map { row ->
            StoredSessionGroup(row.cwd, row.nonce, instant(row.latestUpdatedAt), row.cwd in running)
        }
    }

    public actual suspend fun listSessionsInGroup(
        cwd: String?, includeArchived: Boolean, limit: Int?,
    ): StoredSessionGroupPage = withDbLock {
        requireOpen()
        val active = openSessions.value
        val page = connection.readSessionGroupPage(cwd, includeArchived, limit)
        StoredSessionGroupPage(page.nonce, page.rows.map { entry(it, active) }, page.hiddenCount)
    }

    private fun runningGroupsLocked(): Map<Int, String?> = buildMap {
        for ((id, owner) in openSessions.value) {
            if (owner.coroutineContext[Job]?.isActive != true || owner.runtime.runningTurn.value == null) continue
            // A child Agent has no physical directory row and does not affect root catalog status.
            connection.sessionCatalogGroup(id)?.let { put(id, it.cwd) }
        }
    }

    private fun publishGroupsCommitted(changes: Map<String?, Boolean>) {
        if (changes.isNotEmpty()) {
            groupNonces.committed(changes.mapValues { (cwd, exists) ->
                if (exists) connection.readSessionGroupNonce(cwd) else null
            }, runningGroupsLocked())
        }
    }

    private fun refreshGroupsLocked() {
        mutableEntries.value = inventory()
        groupNonces.reconcile(connection.readSessionGroupNonces(), runningGroupsLocked())
    }

    private suspend fun handleExternalSignal(key: String) {
        if (key == BackendSignalKeys.SessionGroups) {
            withDbLock { if (!closed) refreshGroupsLocked() }
            return
        }
        val parts = key.split('.')
        if (parts.size != 3 || parts[0] != "agent") return
        val agentId = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return
        when (key) {
            BackendSignalKeys.agentRunning(agentId) -> withDbLock { if (!closed) refreshGroupsLocked() }
            BackendSignalKeys.agentTimeline(agentId) -> lifecycleMutex.withLock { refreshTimelineLocked(agentId) }
            BackendSignalKeys.agentAwaitDeadline(agentId) -> withDbLock { if (!closed) refreshDeadlineLocked(agentId) }
            BackendSignalKeys.agentDeleted(agentId) -> lifecycleMutex.withLock { evictDeletedLocked(agentId) }
        }
    }

    private suspend fun resync() {
        lifecycleMutex.withLock {
            for (agentId in openSessions.value.keys) refreshTimelineLocked(agentId)
            withDbLock {
                if (!closed) {
                    mutableAwaitDeadlines.value = queryAwaitDeadlines(connection)
                    refreshGroupsLocked()
                }
            }
        }
    }

    // Storage takes the DB mutex itself. Hold the lifecycle lock to keep the handle alive.
    private suspend fun refreshTimelineLocked(agentId: Int) {
        val session = residency.activeSessionOrNullLocked(agentId) as? SqliteSession ?: return
        try {
            session.storage.refreshObservableState()
        } catch (error: NoSuchElementException) {
            if (withDbLock { agents.get(agentId) != null }) throw error
            // A delete can overtake a timeline hint, or its signal can be lost in an overflow.
            evictDeletedLocked(agentId)
        }
    }

    private suspend fun evictDeletedLocked(agentId: Int) {
        residency.closeLocked(agentId)
        withDbLock { mutableAwaitDeadlines.value -= agentId }
    }

    public actual override suspend fun getEntry(entryIndex: Int): KodexSessionEntry = withDbLock {
        requireOpen()
        requireEntry(entryIndex)
        val activeSessions = openSessions.value
        entry(
            connection.query("$RootCatalog AND root.session_id = ?", listOf(entryIndex.toLong())).single(),
            activeSessions,
        )
    }

    private fun instant(raw: Any?): Instant? = (raw as String?)?.let(Instant::parse)

    private fun entry(
        row: List<Any?>,
        activeSessions: Map<Int, KodexAgentSession>,
    ): KodexSessionEntry {
        val index = (row[0] as Long).toInt()
        val rootId = (row[6] as Long).toInt()
        val owner = activeSessions[rootId]?.takeIf { it.coroutineContext[Job]?.isActive == true }
        return object : KodexSessionEntry {
            override val entryIndex = index
            override val rootAgentId = rootId
            override val archived = row[1] == 1L
            override val name = row[2] as String?
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
        val groupChanges = broker.publishAfterCommit(originHeaders) { signals ->
            connection.transaction {
                if (!agents.archiveRoot(index, value)) return@transaction emptyMap()
                val group = checkNotNull(connection.sessionCatalogGroup(agents.rootId(index)))
                signals.add(BackendSignalKeys.SessionGroups)
                connection.recordSessionGroupMutation(group, group)
            }
        }
        publishGroupsCommitted(groupChanges)
    }

    public actual override suspend fun create(): Int = withDbLock {
        requireOpen()
        createCopyLocked(null)
    }

    public actual override suspend fun createFork(sourceEntryIndex: Int): Int = withDbLock {
        requireOpen()
        val agentId = agents.rootId(sourceEntryIndex)
        createCopyLocked(agentId)
    }

    public actual override suspend fun createAgentFork(
        sourceAgentId: Int,
        expectedOwner: KodexAgentSession?,
        untilExclusive: Int?,
    ): AgentForkCopy? = withDbLock {
        requireOpen()
        if (agents.get(sourceAgentId) == null) return@withDbLock null
        val activeOwner = openSessions.value[sourceAgentId]?.takeIf { it.coroutineContext.job.isActive }
        if (activeOwner !== expectedOwner) throw AgentForkSourceChanged()
        require(activeOwner?.runtime?.runningTurn?.value == null) { "Cannot fork a running Agent." }
        val source = sourceAgentId.toLong()
        val latest = connection.query(
            """SELECT max("index") FROM agent_commits WHERE agent_id = ?""", listOf(source),
        ).single().single() as Long?
        require(latest != null) { "Cannot fork an uninitialized Agent." }
        val last = if (untilExclusive == null) latest else untilExclusive.toLong() - 1
        require(last in 0..latest) { "Invalid fork boundary." }
        val settingsType = AgentCommitType.Settings.wireName
        require(connection.query(
            """SELECT 1 FROM agent_commits WHERE agent_id = ? AND type = ? AND "index" = 0""",
            listOf(source, settingsType),
        ).isNotEmpty()) { "Cannot fork an uninitialized Agent." }
        val payload = connection.query(
            """SELECT payload_json FROM agent_commits WHERE agent_id = ? AND type = ? AND "index" <= ?
                ORDER BY "index" DESC LIMIT 1""",
            listOf(source, settingsType, last),
        ).single().single() as String
        val settings = AgentCommitType.Settings.decodePayloadJson(payload) as KodexAgentSettings
        val checkpoint = connection.query(
            """SELECT max("index") FROM agent_commits WHERE agent_id = ? AND type = ? AND "index" <= ?""",
            listOf(source, AgentCommitType.ContextCompaction.wireName, last),
        ).single().single() as Long? ?: 0L
        AgentForkCopy(createCopyLocked(sourceAgentId, checkpoint..last), settings)
    }

    private fun createCopyLocked(
        sourceAgent: Int?,
        window: LongRange? = null,
    ): Int {
        val (index, targetAgent, groups) = broker.publishAfterCommit(originHeaders) { signals ->
            connection.transaction {
                var candidate = 0L
                inventory().forEach { if (it.toLong() == candidate) candidate++ }
                check(candidate <= Int.MAX_VALUE)
                connection.execute("INSERT INTO sessions(session_id) VALUES (?)", listOf(candidate))
                val targetAgent = agents.createRoot(candidate.toInt())
                if (sourceAgent != null) {
                    val source = sourceAgent.toLong()
                    val target = targetAgent.toLong()
                    val rangeFilter = if (window == null) "" else """ AND ("index" = 0 OR "index" BETWEEN ? AND ?)"""
                    copyForkRowsLocked(
                        target,
                        """SELECT type, "index", payload_json, created_at FROM agent_commits WHERE agent_id = ?$rangeFilter""",
                        listOf(source) + if (window == null) emptyList() else listOf(window.first, window.last),
                    )
                    if (window != null && window.first > 0) carryForkSnapshotsLocked(source, target, window.first)
                    connection.recomputeSessionCatalog(targetAgent)
                    signals.add(BackendSignalKeys.agentAwaitDeadline(targetAgent))
                }
                signals.add(BackendSignalKeys.SessionGroups)
                val group = checkNotNull(connection.sessionCatalogGroup(targetAgent))
                Triple(candidate.toInt(), targetAgent, connection.recordSessionGroupMutation(null, group))
            }
        }
        publishGroupsCommitted(groups)
        if (sourceAgent != null) refreshDeadlineLocked(targetAgent)
        mutableEntries.value = inventory()
        return index
    }

    private fun carryForkSnapshotsLocked(source: Long, target: Long, checkpoint: Long) {
        // A preceding snapshot still governs the start of the new window unless the
        // checkpoint wrote that family itself. Later in-window snapshots still override it.
        for (type in listOf(AgentCommitType.Settings, AgentCommitType.AskUser, AgentCommitType.Unstable)) {
            copyForkRowsLocked(
                target,
                """SELECT prior.type, ? AS "index", prior.payload_json, checkpoint.created_at
                    FROM agent_commits AS prior JOIN agent_commits AS checkpoint
                        ON checkpoint.agent_id = prior.agent_id AND checkpoint.type = ? AND checkpoint."index" = ?
                    WHERE prior.agent_id = ? AND prior.type = ? AND prior."index" < ?
                        AND NOT EXISTS (
                            SELECT 1 FROM agent_commits AS own
                            WHERE own.agent_id = prior.agent_id AND own.type = prior.type AND own."index" = ?
                        )
                    ORDER BY prior."index" DESC LIMIT 1""",
                listOf(checkpoint, AgentCommitType.ContextCompaction.wireName, checkpoint, source, type.wireName, checkpoint, checkpoint),
            )
        }
    }

    private fun copyForkRowsLocked(target: Long, selection: String, parameters: List<Any?>) {
        val emptySteerJson = SteerSnapshot(emptyList()).encodePayloadJson()
        // A fork imports neither the source Agent's armed wait nor entry points to its children.
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
                END, created_at FROM ($selection) WHERE type != ?""",
            listOf(target, AgentCommitType.Steer.wireName, emptySteerJson) + parameters + AgentCommitType.EventSubagentCreated.wireName,
        )
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

    public actual override suspend fun openForAwaitDeadline(agentId: Int, expected: PendingAwaitDeadline): KodexAgentSession? =
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
            val storage = withDbLock {
                requireOpen()
                SqliteAgentStorage(
                    connection = connection,
                    agentId = agentId,
                    mutex = mutex,
                    dispatcher = dispatcher,
                    requireAccess = { check(storageScope.isActive) { "Session storage is closed" } },
                    onHistoryChanged = { changedTypes ->
                        tryPublishSignal(BackendSignalKeys.agentTimeline(agentId))
                        if (changedTypes == null || AgentCommitType.Unstable in changedTypes) {
                            refreshDeadlineLocked(agentId)
                            tryPublishSignal(BackendSignalKeys.agentAwaitDeadline(agentId))
                        }
                    },
                    onSessionGroupsChanged = { changes ->
                        publishGroupsCommitted(changes)
                        tryPublishSignal(BackendSignalKeys.SessionGroups)
                    },
                )
            }
            val state = sessionScope.KodexAgentState(
                client = dependencies.client,
                storage = storage,
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
                    publishStopNotification(record, stopEvent)
                }
            }
            val session = SqliteSession(sessionScope, record.sessionId, storage, runtime)
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

    private fun tryPublishSignal(routingKey: String) {
        try {
            broker.tryPublish(BackendMqExchanges.Broadcast, routingKey, byteArrayOf(), originHeaders)
        } catch (_: Exception) {
            // The mutation has already committed or completed; invalidation is best effort.
        }
    }

    private suspend fun publishStopNotification(record: StoredAgent, event: AgentStopEvent) {
        try {
            val notification = when (event) {
                is AgentStopEvent.AssistantMessage ->
                    AgentNotificationSignal.StopAssistantMessage(record.sessionId, record.agentId, event.commitIndex)
                is AgentStopEvent.UnhandledError ->
                    AgentNotificationSignal.StopUnhandledError(record.sessionId, record.agentId, event.message)
            }
            broker.publishNotification(notification, originHeaders)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            RepositoryLogger.warn(error) { "Cannot publish stop notification for Agent ${record.agentId}." }
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
                val (groupChanges, deletedIds, onSchedulesDeleted) = broker.publishAfterCommit(originHeaders) { signals ->
                    connection.transaction {
                        val group = checkNotNull(connection.sessionCatalogGroup(rootId))
                        val deletedIds = agents.idsInSession(entryIndex)
                        val onSchedulesDeleted = onDeleteInTransaction(entryIndex)
                        connection.execute("DELETE FROM sessions WHERE session_id = ?", listOf(entryIndex.toLong()))
                        signals.add(BackendSignalKeys.SessionGroups)
                        for (id in deletedIds) {
                            signals.add(BackendSignalKeys.agentDeleted(id))
                            signals.add(BackendSignalKeys.agentAwaitDeadline(id))
                        }
                        Triple(connection.recordSessionGroupMutation(group, null), deletedIds, onSchedulesDeleted)
                    }
                }
                onSchedulesDeleted()
                publishGroupsCommitted(groupChanges)
                mutableAwaitDeadlines.value -= deletedIds.toSet()
                mutableEntries.value = inventory()
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
            cleanupResult.await().getOrThrow()
        }
    }
}

private class SqliteSession(
    scope: CoroutineScope,
    override val sessionId: Int,
    override val storage: SqliteAgentStorage,
    override val runtime: AgentRuntime,
) : KodexAgentSession, CoroutineScope by scope

public actual suspend fun openVerifiedKodexDatabase(
    root: Path,
    fileSystem: CoroutineFileSystem,
    allowFresh: Boolean,
    dispatcher: CoroutineDispatcher,
): Pair<Path, SqliteConnection> {
    if (allowFresh) fileSystem.createDirectories(root)
    val resolvedRoot = fileSystem.resolve(root)
    val dbPath = Path(resolvedRoot, "kodex.db")
    val dbMetadata = fileSystem.metadataOrNull(dbPath)
    val fresh = allowFresh && dbMetadata == null && fileSystem.list(resolvedRoot).none {
        it.name in setOf("kodex.db", "version.json", "sessions", ".migrations")
    }
    if (!fresh && (dbMetadata?.isRegularFile != true || dbMetadata.size == 0L || fileSystem.resolve(dbPath) != dbPath)) {
        throw IOException("Authoritative SQLite session database is missing for existing Kodex Home: $dbPath")
    }
    val connection = acquireDatabaseConnection(dispatcher) {
        try {
            val opened = if (fresh) openSqliteConnection(dbPath) else openExistingSqliteConnection(dbPath)
            opened.closeOnFailure { conn ->
                if (fresh) initializeKodexHomeDatabase(conn) else requireKodexHomeDatabase(conn)
                check(conn.query("PRAGMA journal_mode=WAL") == listOf(listOf("wal"))) {
                    "Could not enable SQLite WAL."
                }
                conn.execute("PRAGMA synchronous=FULL")
                conn
            }
        } catch (failure: SqliteFailure) {
            throw IOException("Cannot open authoritative SQLite session database: $dbPath", failure)
        } catch (failure: IllegalStateException) {
            throw IOException("Incompatible authoritative SQLite session database: $dbPath", failure)
        }
    }
    return resolvedRoot to connection
}

internal suspend fun acquireDatabaseConnection(
    dispatcher: CoroutineDispatcher,
    open: () -> SqliteConnection,
): SqliteConnection {
    var acquired: SqliteConnection? = null
    try {
        // Record the handle before crossing a dispatcher boundary that may discard the result.
        val connection = withContext(dispatcher) { open().also { acquired = it } }
        currentCoroutineContext().ensureActive()
        return connection
    } catch (failure: Throwable) {
        try {
            acquired?.let { connection ->
                withContext(NonCancellable) { withContext(dispatcher) { connection.close() } }
            }
        } catch (cleanup: Throwable) {
            if (cleanup !== failure) failure.addSuppressed(cleanup)
        }
        throw failure
    }
}

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
public actual suspend fun CoroutineScope.SqliteKodexSessionRepository(
    root: Path,
    dependencies: KodexAgentDependencies,
    broker: MqBroker,
    fileSystem: CoroutineFileSystem,
    sessionTtl: Duration,
    timeSource: TimeSource,
    onTurnActiveRenewal: suspend () -> Unit,
    turnRenewalInterval: () -> Duration,
): SqliteKodexSessionRepository {
    require(sessionTtl.isPositive()) { "sessionTtl must be positive: $sessionTtl" }
    return newSingleThreadContext("Kodex.SqliteMain").closeOnFailure({ it.close() }) { dispatcher ->
        val (resolvedRoot, openedConnection) =
            openVerifiedKodexDatabase(root, fileSystem, allowFresh = true, dispatcher = dispatcher)
        openedConnection.closeOnFailure({ withContext(dispatcher) { it.close() } }) { connection ->
            supervisorChildScope().closeOnFailure(CoroutineScope::cancelAndJoin) { scope ->
                val initialInventory = withContext(dispatcher) { readInventory(connection) }
                currentCoroutineContext().ensureActive()
                scope.coroutineContext.ensureActive()
                SqliteKodexSessionRepository(
                    scope = scope,
                    root = resolvedRoot,
                    dependencies = dependencies,
                    fileSystem = fileSystem,
                    connection = connection,
                    mutex = Mutex(),
                    dispatcher = dispatcher,
                    ownedDispatcher = dispatcher,
                    initialInventory = initialInventory,
                    sessionTtl = sessionTtl,
                    origin = timeSource.markNow(),
                    onTurnActiveRenewal = onTurnActiveRenewal,
                    turnRenewalInterval = turnRenewalInterval,
                    broker = broker,
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
    broker: MqBroker,
    fileSystem: CoroutineFileSystem,
    sessionTtl: Duration,
    timeSource: TimeSource,
    onTurnActiveRenewal: suspend () -> Unit,
    turnRenewalInterval: () -> Duration,
    dispatcher: CoroutineDispatcher,
    onDeleteInTransaction: (sessionId: Int) -> () -> Unit,
): SqliteKodexSessionRepository {
    require(sessionTtl.isPositive()) { "sessionTtl must be positive: $sessionTtl" }
    val resolvedRoot = fileSystem.resolve(root)
    return supervisorChildScope().closeOnFailure(CoroutineScope::cancelAndJoin) { scope ->
        mutex.withLock {
            withContext(dispatcher) {
                requireKodexHomeDatabase(connection)
                val initialInventory = readInventory(connection)
                currentCoroutineContext().ensureActive()
                scope.coroutineContext.ensureActive()
                SqliteKodexSessionRepository(
                    scope = scope,
                    root = resolvedRoot,
                    dependencies = dependencies,
                    fileSystem = fileSystem,
                    connection = connection,
                    mutex = mutex,
                    dispatcher = dispatcher,
                    initialInventory = initialInventory,
                    sessionTtl = sessionTtl,
                    origin = timeSource.markNow(),
                    onTurnActiveRenewal = onTurnActiveRenewal,
                    turnRenewalInterval = turnRenewalInterval,
                    onDeleteInTransaction = onDeleteInTransaction,
                    broker = broker,
                )
            }
        }
    }
}

private val RepositoryBindings = setOf(
    BackendSignalKeys.SessionGroups,
    BackendSignalKeys.AllAgentRunning,
    BackendSignalKeys.AllAgentTimelines,
    BackendSignalKeys.AllAgentAwaitDeadlines,
    BackendSignalKeys.AllAgentDeleted,
)

internal data class RepositoryInventory(
    val entries: List<Int>,
    val deadlines: Map<Int, PendingAwaitDeadline>,
    val groupNonces: Map<String?, Long>,
)

private fun readInventory(connection: SqliteConnection): RepositoryInventory = RepositoryInventory(
    querySessionInventory(connection), queryAwaitDeadlines(connection), connection.readSessionGroupNonces(),
)

private const val RootCatalog = """
    SELECT root.session_id, root.completed, c.name, c.cwd, c.created_at, c.updated_at, c.root_agent_id
    FROM agents AS root JOIN session_catalog_view AS c ON c.root_agent_id = root.agent_id
    WHERE root.parent_agent_id IS NULL
"""

internal fun rootCatalogQuery(includeArchived: Boolean): String =
    "$RootCatalog " + (if (includeArchived) "" else "AND root.completed = 0 ") + "ORDER BY root.session_id"

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
private val RepositoryLogger = KotlinLogging.logger {}
