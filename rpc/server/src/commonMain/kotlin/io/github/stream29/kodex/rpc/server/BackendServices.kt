@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.server

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.agentcontext.contract.AgentContextSourceSettings
import io.github.stream29.kodex.agenteventbus.AgentEventBus
import io.github.stream29.kodex.agentruntime.contract.AgentStopEvent
import io.github.stream29.kodex.agentruntime.contract.PendingAwaitDeadline
import io.github.stream29.kodex.agentschedule.contract.ScheduleRecord
import io.github.stream29.kodex.agentschedule.contract.ScheduleRepository
import io.github.stream29.kodex.agentschedule.contract.nextCronTriggerAt
import io.github.stream29.kodex.agentschedule.sqlite.SqliteScheduleRepository
import io.github.stream29.kodex.agentsession.contract.*
import io.github.stream29.kodex.agentsession.sqlite.SqliteKodexSessionRepository
import io.github.stream29.kodex.agentsession.sqlite.openVerifiedKodexDatabase
import io.github.stream29.kodex.cli.auth.BackendFileSystemAuthStore
import io.github.stream29.kodex.cli.notification.notifyUnhandledErrorHooks
import io.github.stream29.kodex.cli.sessiontitle.OpenAiSessionTitleGenerator
import io.github.stream29.kodex.cli.sessiontitle.SessionTitleGenerator
import io.github.stream29.kodex.cli.settings.*
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.impl.DefaultMcpOAuthClient
import io.github.stream29.kodex.openai.client.OpenAiClient
import io.github.stream29.kodex.openai.client.OpenAiLoginClient
import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import io.github.stream29.kodex.openai.client.contract.OpenAiClient as Client
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient as LoginClient
import io.github.stream29.kodex.rpc.contract.*
import io.github.stream29.kodex.rpc.models.BackendSettings
import io.github.stream29.kodex.rpc.models.Notification
import io.github.stream29.kodex.rpc.models.OAuthTarget
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.default
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.rpc.RpcServer
import io.github.stream29.kodex.rpc.models.AgentSqlQueryResult
import io.github.stream29.kodex.rpc.models.AgentSqlSchemaResult
import io.github.stream29.kodex.utils.rpcexception.BackendLocalCredentialRequired
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

public actual suspend fun <R> withBackendServices(
    home: Path,
    codexHome: Path,
    agentsHome: Path,
    defaults: BackendSettings,
    createLoginClient: () -> LoginClient,
    createClient: (OpenAiAuthStore) -> Client,
    renewBackend: suspend () -> Unit,
    backendRenewalInterval: () -> Duration,
    checkAdmission: () -> Unit,
    requiresPreparedHome: Boolean,
    clock: Clock,
    block: suspend CoroutineScope.(BackendServices) -> R,
): R = withBackendServicesWithTitleGenerator(
    home = home,
    codexHome = codexHome,
    agentsHome = agentsHome,
    defaults = defaults,
    createLoginClient = createLoginClient,
    createClient = createClient,
    createTitleGenerator = { OpenAiSessionTitleGenerator(it) },
    renewBackend = renewBackend,
    backendRenewalInterval = backendRenewalInterval,
    checkAdmission = checkAdmission,
    requiresPreparedHome = requiresPreparedHome,
    clock = clock,
    block = block,
)

internal suspend fun <R> withBackendServicesWithTitleGenerator(
    home: Path,
    codexHome: Path,
    agentsHome: Path,
    defaults: BackendSettings = defaultBackendSettings(),
    createLoginClient: () -> LoginClient = { OpenAiLoginClient() },
    createClient: (OpenAiAuthStore) -> Client = { OpenAiClient(it) },
    createTitleGenerator: (Client) -> SessionTitleGenerator = { OpenAiSessionTitleGenerator(it) },
    renewBackend: suspend () -> Unit = {},
    backendRenewalInterval: () -> Duration = { 20.seconds },
    checkAdmission: () -> Unit = {},
    requiresPreparedHome: Boolean = false,
    clock: Clock = Clock.System,
    block: suspend CoroutineScope.(BackendServices) -> R,
): R = coroutineScope {
    val owner = supervisorChildScope()
    var login: LoginClient? = null
    var credentials: BackendFileSystemAuthStore? = null
    var client: Client? = null
    var failure: Throwable? = null
    try {
        val unified = UnifiedSettingsStore.open(home, defaults)
        val store = unified.backendStore
        val source = store.settings
            .map { it.authSource }
            .stateIn(owner, SharingStarted.Eagerly, store.settings.value.authSource)
        val loginClient = createLoginClient().also { login = it }
        val auth = owner.BackendFileSystemAuthStore(home, codexHome, source, loginClient).also { credentials = it }
        val api = createClient(auth).also { client = it }
        val mcpOAuth = owner.DefaultMcpOAuthClient()
        withBackendGlobalState(
            unified,
            agentsHome,
            home,
            codexHome,
            api,
            auth,
            mcpOAuth,
            mcpOAuth,
        ) { global ->
            val account = BackendAccountState(owner, auth, api)
            val oauth = BackendOAuth(owner, auth, loginClient, global.mcpManager)
            val owned = BackendOwnedOAuth(
                scope = owner,
                candidates = { target ->
                    when (target) {
                        is OAuthTarget.OpenAi -> listOf(
                            "http://localhost:1455/auth/callback",
                            "http://localhost:1457/auth/callback",
                        )

                        is OAuthTarget.Mcp -> {
                            val server = store.settings.value.mcpServers[target.serverName]
                                as? McpServerConfiguration.StreamableHttp
                            listOf(requireNotNull(server?.oauth?.client?.redirectUri) { "MCP OAuth is not configured." })
                        }
                    }
                },
                begin = oauth::begin,
                waitingState = oauth::waitingState,
                holdPendingDeadline = oauth::holdPendingDeadline,
                complete = oauth::complete,
                cancelInner = oauth::cancel,
                releaseInner = oauth::abandonPending,
                reportCleanupFailure = { BackendLogger.warn { "OAuth resource cleanup failed." } },
            )
            val errors = FrontendErrorQueue(owner, { unified.current.frontend.hooks }) { hooks, message ->
                notifyUnhandledErrorHooks(message, hooks, home)
            }
            val effects = BackendFrontendEffectsImpl(owned, oauth, errors)
            val dependencies = KodexAgentDependencies(
                api,
                global.modelCatalog,
                global.contextSettings,
                global.contextSettings,
                clock,
            )
            val persistence = LazySqliteBackendPersistence(
                owner = owner,
                home = home,
                dependencies = dependencies,
                renewBackend = renewBackend,
                backendRenewalInterval = backendRenewalInterval,
                checkAdmission = checkAdmission,
                requiresPreparedHome = requiresPreparedHome,
            )
            var operationFailure: Throwable? = null
            try {
                val existingDbMeta = SystemCoroutineFileSystem.metadataOrNull(Path(home, "kodex.db"))
                if (requiresPreparedHome || existingDbMeta != null && existingDbMeta.isRegularFile && existingDbMeta.size > 0L) {
                    persistence.acquire(requireExternalAdmission = false)
                }
                val eventBus = AgentEventBus(persistence.sessionRepository)
                persistence.startScheduleRunner(eventBus)
                persistence.startAwaitDeadlineRunner()
                val management = BackendSessionManagement(
                    repository = persistence.sessionRepository,
                    newSessionDefaults = { store.settings.value.newSession },
                    titleSettings = { store.settings.value.sessionTitle },
                    titleGenerator = createTitleGenerator(api),
                    checkAdmission = persistence::ensureAdmission,
                )
                val runtime = BackendAgentRuntimeRpc(
                    repository = persistence.sessionRepository,
                    eventBus = eventBus,
                    client = api,
                    home = home,
                    onAskUserCreated = persistence::emitNotification,
                    checkAdmission = persistence::ensureAdmission,
                    subagentSpawner = BackendSubagentSpawner(
                        repository = persistence.sessionRepository,
                        models = { global.models.value },
                        allocate = { id, owner -> persistence.acquire().first.createChild(id, owner) },
                        discardUninitialized = { id -> persistence.acquire().first.discardUninitializedChild(id) },
                        record = eventBus::recordSubagentCreation,
                        submit = eventBus::submitUserMessage,
                    ),
                    archiveSession = management::archiveSession,
                    markChildCompleted = { id -> persistence.acquire().first.markChildCompleted(id) },
                )
                val service = BackendGlobalRpc(
                    scope = owner,
                    settings = global,
                    account = account,
                    effects = effects,
                    sessions = management,
                    catalogRepository = { persistence.acquire().first },
                    schedules = persistence.scheduleRepository,
                    home = home,
                    checkAdmission = persistence::ensureAdmission,
                )
                val services = BackendServices(
                    global = service,
                    runtime = runtime,
                    eventBus = eventBus,
                    schedules = persistence.scheduleRepository,
                    settings = global,
                    notifications = persistence.notifications,
                    persistence = persistence,
                )
                coroutineScope {
                    // Throw in the graph's scope rather than the independent persistence scope.
                    // The host closes admission before waiting for persistence cleanup.
                    val runnerMonitor = launch(start = CoroutineStart.UNDISPATCHED) {
                        throw persistence.runnerFailure.await()
                    }
                    try {
                        block(services)
                    } finally {
                        runnerMonitor.cancel()
                    }
                }
            } catch (error: Throwable) {
                operationFailure = error
                throw error
            } finally {
                closeBackendResources(
                    operationFailure,
                    { persistence.closeAndDrain() },
                    { effects.closeAndJoin() },
                    { oauth.closeAndJoin() },
                    { account.closeAndJoin() },
                )
            }
        }
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        closeBackendResources(
            failure,
            { credentials?.close() },
            { owner.cancelAndJoin() },
            { client?.close() },
            { login?.close() },
        )
    }
}

/** Registered exactly once per shared connection; the graph remains owned by its host. */
public actual class BackendServices internal constructor(
    public actual val global: GlobalRpc,
    public actual val runtime: AgentRuntimeRpc,
    internal val eventBus: AgentEventBus,
    internal val schedules: ScheduleRepository,
    public actual val settings: BackendGlobalState,
    public actual val notifications: Flow<Notification>,
    private val persistence: LazySqliteBackendPersistence,
) {
    internal val notificationSubscriptionCount: StateFlow<Int>
        get() = persistence.notificationSubscriptionCount

    public actual suspend fun shutdownSessions(): Unit = persistence.shutdownSessions()

    public actual fun register(server: RpcServer): Unit = register(server, allowAgentSql = false)

    public actual fun register(server: RpcServer, allowAgentSql: Boolean) {
        val connectionGlobal = if (allowAgentSql) global else object : GlobalRpc by global {
            override suspend fun queryAgentSql(sql: String): AgentSqlQueryResult = throw BackendLocalCredentialRequired()
            override suspend fun getAgentSqlSchema(): AgentSqlSchemaResult = throw BackendLocalCredentialRequired()
        }
        server.registerService(GlobalRpc::class) { connectionGlobal }
        server.registerService(AgentRuntimeRpc::class) { runtime }
    }
}

/** Projects [IndexedAgentStopEvent]s emitted by a Session repository into RPC [Notification]s. */
internal fun Flow<IndexedAgentStopEvent>.toNotifications(): Flow<Notification> =
    map { (entryIndex, stopEvent, agentId) ->
        when (stopEvent) {
            is AgentStopEvent.AssistantMessage -> Notification.Stop.AssistantMessage(entryIndex, stopEvent.message, agentId)
            is AgentStopEvent.UnhandledError -> Notification.Stop.UnhandledError(entryIndex, stopEvent.message, agentId)
        }
    }

public actual fun defaultBackendSettings(): BackendSettings = BackendSettings(
    authSource = KodexAuthSource.Codex,
    shell = Shell.default,
    contextSources = AgentContextSourceSettings(),
    newSession = KodexNewSessionSettings(),
    sessionTitle = SessionTitleSettings(),
    mcpServers = emptyMap(),
)

internal class LazySqliteBackendPersistence(
    owner: CoroutineScope,
    private val home: Path,
    private val dependencies: KodexAgentDependencies,
    private val renewBackend: suspend () -> Unit,
    private val backendRenewalInterval: () -> Duration,
    private val checkAdmission: () -> Unit,
    private val requiresPreparedHome: Boolean = false,
    private val sessionTtl: Duration = DefaultKodexSessionTtl,
) {
    private val persistenceScope: CoroutineScope =
        CoroutineScope(owner.coroutineContext.minusKey(Job) + SupervisorJob())
    private val acquisitionMutex = Mutex()
    private val dbMutex = Mutex()
    private val drainMutex = Mutex()
    private var drainResult: Result<Unit>? = null

    @kotlin.concurrent.Volatile
    private var stopping = false
    private var acquiredDatabase: Pair<SqliteConnection, CloseableCoroutineDispatcher>? = null
    private var acquiredSessions: SqliteKodexSessionRepository? = null
    private var acquiredSchedules: SqliteScheduleRepository? = null
    private var scheduleRunnerJob: Job? = null
    private var awaitDeadlineRunnerJob: Job? = null
    val runnerFailure = CompletableDeferred<Throwable>()

    private val lazyEntries = MutableStateFlow<List<Int>>(emptyList())
    private val lazyOpenSessions = MutableStateFlow<Map<Int, KodexAgentSession>>(emptyMap())
    private val lazyAwaitDeadlines = MutableStateFlow<Map<Int, PendingAwaitDeadline>>(emptyMap())
    private val notificationsShared = MutableSharedFlow<Notification>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val lazyNextTriggerAt = MutableStateFlow<Instant?>(null)

    val notifications: Flow<Notification> = notificationsShared.asSharedFlow()
    val notificationSubscriptionCount: StateFlow<Int> get() = notificationsShared.subscriptionCount

    fun emitNotification(notification: Notification) {
        notificationsShared.tryEmit(notification)
    }

    val sessionRepository: KodexSessionRepository = object : KodexSessionRepository {
        override val coroutineContext: CoroutineContext
            get() = persistenceScope.coroutineContext
        override val entries: StateFlow<List<Int>> = lazyEntries.asStateFlow()
        override val openSessions: StateFlow<Map<Int, KodexAgentSession>> = lazyOpenSessions.asStateFlow()
        override val pendingAwaitDeadlines: StateFlow<Map<Int, PendingAwaitDeadline>> = lazyAwaitDeadlines.asStateFlow()
        override val stopEvents: Flow<IndexedAgentStopEvent> = emptyFlow()

        override suspend fun list(): List<Int> = acquire().first.list()
        override suspend fun listEntries(includeArchived: Boolean): List<KodexSessionEntry> =
            acquire().first.listEntries(includeArchived)

        override suspend fun getEntry(entryIndex: Int): KodexSessionEntry =
            acquire().first.getEntry(entryIndex)

        override suspend fun getRootAgentId(entryIndex: Int): Int = acquire().first.getRootAgentId(entryIndex)

        override suspend fun getAgentRecord(agentId: Int): StoredAgent? = acquire().first.getAgentRecord(agentId)

        override suspend fun openAgent(agentId: Int): KodexAgentSession? {
            val sessions = acquire().first
            return sessions.openAgent(agentId).also { lazyOpenSessions.value = sessions.openSessions.value }
        }

        override suspend fun create(): Int {
            val sessions = acquire().first
            return sessions.create().also { lazyEntries.value = sessions.entries.value }
        }

        override suspend fun createFork(sourceEntryIndex: Int): Int {
            val sessions = acquire().first
            return sessions.createFork(sourceEntryIndex).also { lazyEntries.value = sessions.entries.value }
        }

        override suspend fun createAgentFork(
            sourceAgentId: Int,
            expectedOwner: KodexAgentSession?,
            untilExclusive: Int?,
        ) = acquire().first.let { sessions ->
            sessions.createAgentFork(sourceAgentId, expectedOwner, untilExclusive).also {
                lazyEntries.value = sessions.entries.value
            }
        }

        override suspend fun open(entryIndex: Int): KodexAgentSession {
            val sessions = acquire().first
            return sessions.open(entryIndex).also { lazyOpenSessions.value = sessions.openSessions.value }
        }

        override suspend fun closeSession(entryIndex: Int) {
            val sessions = acquisitionMutex.withLock { acquiredSessions } ?: return
            sessions.closeSession(entryIndex)
            lazyOpenSessions.value = sessions.openSessions.value
        }

        override suspend fun closeAllSessions() {
            val sessions = acquisitionMutex.withLock { acquiredSessions } ?: return
            sessions.closeAllSessions()
            lazyOpenSessions.value = sessions.openSessions.value
        }

        override suspend fun delete(entryIndex: Int) {
            val (sessions, schedules) = acquire()
            sessions.delete(entryIndex)
            withContext(NonCancellable) {
                lazyEntries.value = sessions.entries.value
                lazyOpenSessions.value = sessions.openSessions.value
                lazyNextTriggerAt.value = schedules.nextTriggerAt.value
            }
        }
    }

    val scheduleRepository: ScheduleRepository = object : ScheduleRepository {
        override val nextTriggerAt: StateFlow<Instant?> = lazyNextTriggerAt.asStateFlow()

        override suspend fun create(
            agentId: Int,
            triggerAt: Instant,
            cron: String?,
            description: String,
            createdAt: Instant,
        ): ScheduleRecord {
            val schedules = acquire().second
            return schedules.create(agentId, triggerAt, cron, description, createdAt).also {
                lazyNextTriggerAt.value = schedules.nextTriggerAt.value
            }
        }

        override suspend fun list(agentId: Int): List<ScheduleRecord> =
            acquire().second.list(agentId)

        override suspend fun listDue(now: Instant): List<ScheduleRecord> =
            acquire().second.listDue(now)

        override suspend fun advance(scheduleId: Long, nextTriggerAt: Instant): Boolean {
            val schedules = acquire().second
            return schedules.advance(scheduleId, nextTriggerAt).also {
                lazyNextTriggerAt.value = schedules.nextTriggerAt.value
            }
        }

        override suspend fun delete(scheduleId: Long): Boolean {
            val schedules = acquire().second
            return schedules.delete(scheduleId).also {
                lazyNextTriggerAt.value = schedules.nextTriggerAt.value
            }
        }
    }

    private fun ensureOpen() {
        persistenceScope.ensureActive()
        check(!stopping) { "Backend is stopping." }
    }

    fun ensureAdmission() {
        ensureOpen()
        checkAdmission()
    }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    suspend fun acquire(
        requireExternalAdmission: Boolean = true,
    ): Pair<SqliteKodexSessionRepository, SqliteScheduleRepository> =
        acquisitionMutex.withLock {
            if (requireExternalAdmission) ensureAdmission() else ensureOpen()
            val existingSessions = acquiredSessions
            val existingSchedules = acquiredSchedules
            if (existingSessions != null && existingSchedules != null) {
                return@withLock existingSessions to existingSchedules
            }
            val dispatcher = newSingleThreadContext("Kodex.SqliteMain")
            val (resolvedHome, connection) = try {
                openVerifiedKodexDatabase(
                    home, SystemCoroutineFileSystem, allowFresh = !requiresPreparedHome, dispatcher = dispatcher,
                )
            } catch (error: Throwable) {
                closeBackendResources(error, { dispatcher.close() })
                throw error
            }
            try {
                lateinit var schedules: SqliteScheduleRepository
                val sessions = persistenceScope.SqliteKodexSessionRepository(
                    root = resolvedHome,
                    connection = connection,
                    mutex = dbMutex,
                    dispatcher = dispatcher,
                    dependencies = dependencies,
                    sessionTtl = sessionTtl,
                    onTurnActiveRenewal = renewBackend,
                    turnRenewalInterval = backendRenewalInterval,
                    onDeleteInTransaction = { sessionId ->
                        schedules.deleteForSessionInTransaction(sessionId)
                    },
                )
                try {
                    schedules = SqliteScheduleRepository(
                        connection = connection,
                        mutex = dbMutex,
                        dispatcher = dispatcher,
                        requireAccess = { persistenceScope.ensureActive() },
                    )
                    currentCoroutineContext().ensureActive()
                    if (requireExternalAdmission) ensureAdmission() else ensureOpen()
                    lazyEntries.value = sessions.entries.value
                    lazyOpenSessions.value = sessions.openSessions.value
                    lazyAwaitDeadlines.value = sessions.pendingAwaitDeadlines.value
                    lazyNextTriggerAt.value = schedules.nextTriggerAt.value
                    persistenceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        sessions.entries.collect { lazyEntries.value = it }
                    }
                    persistenceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        sessions.openSessions.collect { lazyOpenSessions.value = it }
                    }
                    persistenceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        sessions.pendingAwaitDeadlines.collect { lazyAwaitDeadlines.value = it }
                    }
                    persistenceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        schedules.nextTriggerAt.collect { lazyNextTriggerAt.value = it }
                    }
                    persistenceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        sessions.stopEvents.toNotifications().collect { notification ->
                            notificationsShared.tryEmit(notification)
                        }
                    }
                    acquiredDatabase = connection to dispatcher
                    acquiredSessions = sessions
                    acquiredSchedules = schedules
                    sessions to schedules
                } catch (error: Throwable) {
                    closeBackendResources(error, { sessions.closeAndJoin() })
                    throw error
                }
            } catch (error: Throwable) {
                closeBackendResources(
                    error,
                    { dbMutex.withLock { withContext(dispatcher) { connection.close() } } },
                    { dispatcher.close() },
                )
                throw error
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun startScheduleRunner(eventBus: AgentEventBus) {
        scheduleRunnerJob = persistenceScope.launchBackendRunner("schedule", { stopping }, runnerFailure) {
            runBackendRunnerLoop({ stopping }, { error ->
                BackendLogger.error(error) { "Schedule runner failed; will retry." }
            }) {
                val wakeTarget = lazyNextTriggerAt.transformLatest { nextTrigger ->
                    if (nextTrigger == null) return@transformLatest
                    val waitDuration = nextTrigger - dependencies.clock.now()
                    if (waitDuration.isPositive()) {
                        delay(waitDuration)
                    }
                    emit(nextTrigger)
                }.first()

                ensureOpen()
                if (!scheduleAdmissionOpen()) {
                    delay(20.milliseconds)
                    return@runBackendRunnerLoop
                }
                val (sessions, schedules) = acquire(requireExternalAdmission = false)
                val fireTime = dependencies.clock.now()
                val due = schedules.listDue(fireTime)
                if (due.isEmpty()) {
                    if (fireTime < wakeTarget) {
                        delay(wakeTarget - fireTime + 1.milliseconds)
                    }
                    return@runBackendRunnerLoop
                }
                var hadDeliveryFailure = false
                for (record in due) {
                    ensureOpen()
                    if (!scheduleAdmissionOpen()) {
                        hadDeliveryFailure = true
                        break
                    }
                    val shouldBackoff = withContext(NonCancellable) {
                        var retryNeeded = false
                        val delivered = try {
                            eventBus.submitScheduleTrigger(
                                agentId = record.agentId,
                                scheduleId = record.scheduleId,
                                description = record.description,
                            )
                            true
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (deliveryFailure: Exception) {
                            val targetMissing = try {
                                sessions.getAgentRecord(record.agentId) == null
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (_: Exception) {
                                false
                            }
                            if (targetMissing) {
                                BackendLogger.warn(deliveryFailure) {
                                    "Dropping orphan schedule ${record.scheduleId} for missing agent ${record.agentId}."
                                }
                                schedules.delete(record.scheduleId)
                                lazyNextTriggerAt.value = schedules.nextTriggerAt.value
                            } else {
                                BackendLogger.warn(deliveryFailure) {
                                    "Failed to deliver schedule ${record.scheduleId} to agent ${record.agentId}; retaining for retry."
                                }
                                retryNeeded = true
                            }
                            false
                        }
                        if (delivered) {
                            val cronExpr = record.cron
                            if (cronExpr == null) {
                                schedules.delete(record.scheduleId)
                            } else {
                                val nextAt = try {
                                    nextCronTriggerAt(cronExpr, maxOf(fireTime, record.triggerAt))
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (cronError: Exception) {
                                    BackendLogger.warn(cronError) {
                                        "Invalid cron expression on schedule ${record.scheduleId}; deleting."
                                    }
                                    null
                                }
                                if (nextAt == null) {
                                    schedules.delete(record.scheduleId)
                                } else {
                                    schedules.advance(record.scheduleId, nextAt)
                                }
                            }
                            lazyNextTriggerAt.value = schedules.nextTriggerAt.value
                        }
                        retryNeeded
                    }
                    if (shouldBackoff) {
                        hadDeliveryFailure = true
                    }
                }
                if (hadDeliveryFailure) {
                    delay(100.milliseconds)
                }
            }
        }
    }

    private fun scheduleAdmissionOpen(): Boolean = try {
        checkAdmission()
        true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: IllegalStateException) {
        false
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun startAwaitDeadlineRunner() {
        awaitDeadlineRunnerJob = persistenceScope.launchBackendRunner("await_event", { stopping }, runnerFailure) {
            val runnerScope = this
            val inFlight = MutableStateFlow(emptySet<Int>())
            val clock = dependencies.clock
            runBackendRunnerLoop({ stopping }, { error ->
                BackendLogger.error(error) { "Await deadline runner failed; will retry." }
            }) {
                val candidates = combine(lazyAwaitDeadlines, inFlight) { deadlines, busy ->
                    deadlines.filterKeys { it !in busy }
                }.transformLatest { deadlines ->
                    val earliest = deadlines.values.minOfOrNull { it.timeoutAt } ?: return@transformLatest
                    val remaining = earliest - clock.now()
                    if (remaining.isPositive()) delay(remaining)
                    val now = clock.now()
                    emit(deadlines.filterValues { it.timeoutAt <= now })
                }.first()
                for ((agentId, expected) in candidates) {
                    inFlight.update { it + agentId }
                    runnerScope.launch {
                        try {
                            ensureAdmission()
                            val repository = acquisitionMutex.withLock { acquiredSessions }
                            repository?.openForAwaitDeadline(agentId, expected)
                                ?.runtime?.requestAwaitDeadlineCheck(expected)?.join()
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Exception) {
                            if (!stopping) {
                                BackendLogger.warn(error) { "Could not check await_event deadline for Agent $agentId; will retry." }
                            }
                        } finally {
                            try {
                                // A still-current deadline may have met a temporarily unavailable Agent or backend.
                                if (lazyAwaitDeadlines.value[agentId] == expected) delay(200.milliseconds)
                            } finally {
                                inFlight.update { it - agentId }
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun shutdownSessions(): Unit = withContext(NonCancellable) {
        val sessions = acquisitionMutex.withLock {
            stopping = true
            scheduleRunnerJob?.cancel()
            awaitDeadlineRunnerJob?.cancel()
            acquiredSessions
        }
        scheduleRunnerJob?.join()
        awaitDeadlineRunnerJob?.join()
        sessions?.closeAllSessions()
    }

    suspend fun closeAndDrain(): Unit = withContext(NonCancellable) {
        drainMutex.withLock {
            val result = drainResult ?: runCatching {
                val (sessions, database) = acquisitionMutex.withLock {
                    stopping = true
                    scheduleRunnerJob?.cancel()
                    awaitDeadlineRunnerJob?.cancel()
                    val current = acquiredSessions to acquiredDatabase
                    acquiredSessions = null
                    acquiredSchedules = null
                    acquiredDatabase = null
                    current
                }
                closeBackendResources(
                    null,
                    { scheduleRunnerJob?.join() },
                    { awaitDeadlineRunnerJob?.join() },
                    { sessions?.closeAndJoin() },
                    { persistenceScope.cancelAndJoin() },
                    {
                        if (database != null) {
                            val (connection, dispatcher) = database
                            dbMutex.withLock { withContext(dispatcher) { connection.close() } }
                        }
                    },
                    { database?.second?.close() },
                )
            }.also { drainResult = it }
            result.getOrThrow()
        }
    }
}

private val BackendLogger = KotlinLogging.logger {}
