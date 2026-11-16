@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.server

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.agentcontext.contract.AgentContextSourceSettings
import io.github.stream29.kodex.agenteventbus.AgentEventBus
import io.github.stream29.kodex.agentschedule.contract.ScheduleRecord
import io.github.stream29.kodex.agentschedule.contract.ScheduleRepository
import io.github.stream29.kodex.agentschedule.contract.nextCronTriggerAt
import io.github.stream29.kodex.agentschedule.sqlite.SqliteScheduleRepository
import io.github.stream29.kodex.agentsession.contract.*
import io.github.stream29.kodex.agentsession.sqlite.SqliteKodexSessionRepository
import io.github.stream29.kodex.agentsession.sqlite.openVerifiedKodexDatabase
import io.github.stream29.kodex.cli.auth.BackendFileSystemAuthStore
import io.github.stream29.kodex.cli.notification.collectNotificationHooks
import io.github.stream29.kodex.cli.sessiontitle.SessionTitleGenerator
import io.github.stream29.kodex.cli.settings.*
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.impl.DefaultMcpOAuthClient
import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import io.github.stream29.kodex.openai.client.contract.OpenAiClient as Client
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient as LoginClient
import io.github.stream29.kodex.rpc.contract.*
import io.github.stream29.kodex.rpc.models.BackendSettings
import io.github.stream29.kodex.rpc.models.Notification
import io.github.stream29.kodex.rpc.models.OAuthTarget
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.closeOnFailure
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.default
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import io.github.stream29.kodex.utils.sqlite.openSqliteConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.rpc.RpcServer
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

public actual suspend fun <R> withBackendServices(
    home: Path,
    codexHome: Path,
    agentsHome: Path,
    defaults: BackendSettings,
    createLoginClient: () -> LoginClient,
    createClient: (OpenAiAuthStore) -> Client,
    createTitleGenerator: (Client) -> SessionTitleGenerator,
    renewBackend: suspend () -> Unit,
    backendRenewalInterval: () -> Duration,
    checkAdmission: () -> Unit,
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
            store,
            unified,
            agentsHome,
            home,
            codexHome,
            api,
            auth,
            mcpOAuth,
            BackendMcpOAuthBridge(mcpOAuth::prepare),
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
                collectNotificationHooks(emptyFlow(), { hooks }, home, flowOf(message))
            }
            val effects = BackendFrontendEffectsImpl(owned, oauth, errors)
            val dependencies = KodexAgentDependencies(
                api,
                global.modelCatalog,
                global.contextSettings,
                global.contextSettings,
            )
            val persistence = LazySqliteBackendPersistence(
                owner = owner,
                home = home,
                dependencies = dependencies,
                renewBackend = renewBackend,
                backendRenewalInterval = backendRenewalInterval,
                checkAdmission = checkAdmission,
            )
            var operationFailure: Throwable? = null
            try {
                val existingDbMeta = SystemCoroutineFileSystem.metadataOrNull(Path(home, "kodex.db"))
                if (existingDbMeta != null && existingDbMeta.isRegularFile && existingDbMeta.size > 0L) {
                    persistence.acquire(requireExternalAdmission = false)
                }
                val eventBus = AgentEventBus(persistence.sessionRepository)
                persistence.startScheduleRunner(eventBus)
                val runtime = BackendAgentRuntimeRpc(
                    repository = persistence.sessionRepository,
                    eventBus = eventBus,
                    client = api,
                    home = home,
                    onAskUserCreated = persistence::emitNotification,
                    checkAdmission = persistence::ensureAdmission,
                )
                val management = BackendSessionManagement(
                    repository = persistence.sessionRepository,
                    newSessionDefaults = { store.settings.value.newSession },
                    titleSettings = { store.settings.value.sessionTitle },
                    titleGenerator = createTitleGenerator(api),
                    reportBackgroundFailure = {
                        BackendLogger.error(it) { "Background Session operation failed." }
                    },
                    checkAdmission = persistence::ensureAdmission,
                )
                val service = BackendGlobalRpc(
                    scope = owner,
                    settings = global,
                    account = account,
                    effects = effects,
                    sessions = management,
                    notifications = persistence.notifications,
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
                    block(services)
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
    public actual val eventBus: AgentEventBus,
    public actual val schedules: ScheduleRepository,
    public actual val settings: BackendGlobalState,
    public actual val notifications: Flow<Notification>,
    private val persistence: LazySqliteBackendPersistence,
) {
    internal val notificationSubscriptionCount: StateFlow<Int>
        get() = persistence.notificationSubscriptionCount

    public actual suspend fun shutdownSessions(): Unit = persistence.shutdownSessions()

    public actual fun register(server: RpcServer) {
        server.registerService(GlobalRpc::class) { global }
        server.registerService(AgentRuntimeRpc::class) { runtime }
        server.registerService(AgentStorageRpc::class) {
            BackendAgentStorageRpc(
                repository = persistence.sessionRepository,
                checkAdmission = persistence::ensureAdmission,
            )
        }
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
) {
    private val persistenceScope: CoroutineScope =
        CoroutineScope(owner.coroutineContext.minusKey(Job) + SupervisorJob())
    private val acquisitionMutex = Mutex()
    private val dbMutex = Mutex()

    @kotlin.concurrent.Volatile
    private var stopping = false
    private var acquiredConnection: SqliteConnection? = null
    private var acquiredSessions: SqliteKodexSessionRepository? = null
    private var acquiredSchedules: SqliteScheduleRepository? = null
    private var scheduleRunnerJob: Job? = null

    private val lazyEntries = MutableStateFlow<List<Int>>(emptyList())
    private val lazyOpenSessions = MutableStateFlow<Map<Int, KodexAgentSession>>(emptyMap())
    private val lazyStopEvents = MutableSharedFlow<IndexedAgentStopEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
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
        override val stopEvents: Flow<IndexedAgentStopEvent> = lazyStopEvents.asSharedFlow()

        override suspend fun list(): List<Int> = acquire().first.list()
        override suspend fun listEntries(includeArchived: Boolean): List<KodexSessionEntry> =
            acquire().first.listEntries(includeArchived)

        override suspend fun getEntry(entryIndex: Int): KodexSessionEntry =
            acquire().first.getEntry(entryIndex)

        override suspend fun create(): Int {
            val sessions = acquire().first
            return sessions.create().also { lazyEntries.value = sessions.entries.value }
        }

        override suspend fun createFork(sourceEntryIndex: Int): Int {
            val sessions = acquire().first
            return sessions.createFork(sourceEntryIndex).also { lazyEntries.value = sessions.entries.value }
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
            lazyEntries.value = sessions.entries.value
            lazyOpenSessions.value = sessions.openSessions.value
            schedules.list(entryIndex).forEach { schedule ->
                schedules.delete(schedule.scheduleId)
            }
            lazyNextTriggerAt.value = schedules.nextTriggerAt.value
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
            val (resolvedHome, connection) = openVerifiedKodexDatabase(home, SystemCoroutineFileSystem)
            try {
                val sessions = persistenceScope.SqliteKodexSessionRepository(
                    root = resolvedHome,
                    connection = connection,
                    mutex = dbMutex,
                    dependencies = dependencies,
                    onTurnActiveRenewal = renewBackend,
                    turnRenewalInterval = backendRenewalInterval,
                    reportMirrorFailure = { mirrorError ->
                        BackendLogger.warn(mirrorError) { "Session file mirror write failed." }
                    },
                )
                try {
                    val schedules = SqliteScheduleRepository(
                        connection = connection,
                        mutex = dbMutex,
                        requireAccess = { persistenceScope.ensureActive() },
                    )
                    currentCoroutineContext().ensureActive()
                    if (requireExternalAdmission) ensureAdmission() else ensureOpen()
                    acquiredConnection = connection
                    acquiredSessions = sessions
                    acquiredSchedules = schedules
                    lazyEntries.value = sessions.entries.value
                    lazyOpenSessions.value = sessions.openSessions.value
                    lazyNextTriggerAt.value = schedules.nextTriggerAt.value
                    persistenceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        sessions.entries.collect { lazyEntries.value = it }
                    }
                    persistenceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        sessions.openSessions.collect { lazyOpenSessions.value = it }
                    }
                    persistenceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        sessions.stopEvents.collect { stop ->
                            lazyStopEvents.tryEmit(stop)
                        }
                    }
                    persistenceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        sessions.stopEvents.toNotifications().collect { notification ->
                            notificationsShared.tryEmit(notification)
                        }
                    }
                    persistenceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        schedules.nextTriggerAt.collect { lazyNextTriggerAt.value = it }
                    }
                    sessions to schedules
                } catch (error: Throwable) {
                    withContext(NonCancellable) { sessions.closeAndJoin() }
                    throw error
                }
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    dbMutex.withLock { withContext(Dispatchers.IO) { connection.close() } }
                }
                throw error
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun startScheduleRunner(eventBus: AgentEventBus) {
        scheduleRunnerJob = persistenceScope.launch {
            while (isActive && !stopping) {
                val wakeTarget = lazyNextTriggerAt.transformLatest { nextTrigger ->
                    if (nextTrigger == null) return@transformLatest
                    val waitDuration = nextTrigger - Clock.System.now()
                    if (waitDuration.isPositive()) {
                        delay(waitDuration)
                    }
                    emit(nextTrigger)
                }.first()

                ensureOpen()
                if (runCatching { checkAdmission() }.isFailure) {
                    delay(20.milliseconds)
                    continue
                }
                val (sessions, schedules) = acquire(requireExternalAdmission = false)
                val fireTime = Clock.System.now()
                val due = schedules.listDue(fireTime)
                if (due.isEmpty()) {
                    if (fireTime < wakeTarget) {
                        delay(wakeTarget - fireTime + 1.milliseconds)
                    }
                    continue
                }
                var hadDeliveryFailure = false
                for (record in due) {
                    ensureOpen()
                    if (runCatching { checkAdmission() }.isFailure) {
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
                        } catch (deliveryFailure: Throwable) {
                            val targetMissing = runCatching {
                                record.agentId !in sessions.list()
                            }.getOrDefault(false)
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
                                val nextAt = runCatching {
                                    nextCronTriggerAt(cronExpr, maxOf(fireTime, record.triggerAt))
                                }.getOrElse { cronError ->
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

    suspend fun shutdownSessions(): Unit = withContext(NonCancellable) {
        val sessions = acquisitionMutex.withLock {
            stopping = true
            scheduleRunnerJob?.cancel()
            acquiredSessions
        }
        scheduleRunnerJob?.join()
        sessions?.closeAllSessions()
    }

    suspend fun closeAndDrain(): Unit = withContext(NonCancellable) {
        val (sessions, connection) = acquisitionMutex.withLock {
            stopping = true
            scheduleRunnerJob?.cancel()
            val currentSessions = acquiredSessions
            val currentConnection = acquiredConnection
            acquiredSessions = null
            acquiredSchedules = null
            acquiredConnection = null
            currentSessions to currentConnection
        }
        try {
            scheduleRunnerJob?.join()
            sessions?.closeAndJoin()
        } finally {
            try {
                persistenceScope.cancelAndJoin()
            } finally {
                if (connection != null) {
                    dbMutex.withLock {
                        withContext(Dispatchers.IO) {
                            connection.close()
                        }
                    }
                }
            }
        }
    }
}

private val BackendLogger = KotlinLogging.logger {}
