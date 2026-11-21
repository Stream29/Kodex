@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.github.stream29.kodex.backend.host

import io.github.stream29.kodex.app.migration.CurrentKodexApplicationVersion
import io.github.stream29.kodex.app.migration.KodexHomeHandle
import io.github.stream29.kodex.app.migration.preflightKodexHome
import io.github.stream29.kodex.app.migration.prepareKodexHome
import io.github.stream29.kodex.backend.*
import io.github.stream29.kodex.cli.notification.collectNotificationHooks
import io.github.stream29.kodex.rpc.control.BackendControlClient
import io.github.stream29.kodex.rpc.localserver.BackendControl
import io.github.stream29.kodex.rpc.localserver.LocalBrowserAccess
import io.github.stream29.kodex.rpc.localserver.BackendConnectionAdmission
import io.github.stream29.kodex.rpc.control.backendControlRequest
import io.github.stream29.kodex.rpc.localserver.withLocalBackendServer
import io.github.stream29.kodex.rpc.server.BackendServices
import io.github.stream29.kodex.rpc.server.withBackendServices
import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.logging.ApplicationLoggingSession
import io.github.stream29.kodex.utils.logging.initializeLogging
import io.github.stream29.kodex.utils.osenvironment.processId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

public actual suspend fun runBackendHost(
    home: Path,
    codexHome: Path,
    agentsHome: Path,
    createClient: (OpenAiAuthStore) -> OpenAiClient,
    createLoginClient: () -> OpenAiLoginClient,
): Unit = coroutineScope {
    suspend fun resolveDirectory(defaultDirectory: Path, requested: String?): String {
        require(requested == null || '\u0000' !in requested) { "The browser working directory contains NUL." }
        val candidate = requested?.let(::Path) ?: defaultDirectory
        require(candidate.isAbsolute) { "The browser working directory must be absolute on the backend." }
        val resolved = SystemCoroutineFileSystem.resolve(candidate)
        require(SystemCoroutineFileSystem.metadataOrNull(resolved)?.isDirectory == true) {
            "The browser working directory is unavailable."
        }
        return resolved.toString()
    }
    val hostJob = currentCoroutineContext().job
    val absoluteHome = if (home.isAbsolute) home else Path(SystemCoroutineFileSystem.resolve(Path(".")), home.toString())
    preflightKodexHome(absoluteHome)
    val instance = BackendInstance.acquire(absoluteHome)
    val lifecycle = createBackendLifecycle()
    val replyFinished = CompletableDeferred<Unit>()
    var services: BackendServices? = null
    var failed: Throwable? = null
    var port = 0
    var published: BackendEndpoint? = null
    var logging: ApplicationLoggingSession? = null
    var preparedHome: KodexHomeHandle? = null
    var primary: Throwable? = null
    fun reason(failure: Throwable): String {
        val diagnostic = if (failure is CancellationException) failure.cause ?: failure else failure
        return diagnostic.message?.takeIf { it.isNotBlank() } ?: diagnostic::class.simpleName ?: "Backend failed."
    }
    suspend fun reportFailure(failure: Throwable) {
        val endpoint = published ?: return
        try {
            instance.publish(BackendEndpoint(endpoint.instanceId, endpoint.pid, endpoint.port, endpoint.password,
                endpoint.applicationVersion, endpoint.businessProtocol, endpoint.controlProtocol,
                reason(failure)))
        } catch (reportFailure: Throwable) {
            if (failure !== reportFailure) failure.addSuppressed(reportFailure)
        }
    }
    suspend fun coreEnded() {
        if (lifecycle.state.value == BackendPhase.Starting || lifecycle.state.value == BackendPhase.Ready) {
            if (lifecycle.requestStop() == BackendStopAcceptance.Accepted) {
                if (hostJob.isActive && failed == null) failed = IllegalStateException("Backend core ended unexpectedly.")
                replyFinished.complete(Unit)
            }
        }
    }
    try {
        val resolvedHome = SystemCoroutineFileSystem.resolve(absoluteHome)
        rejectLiveLegacyOwner(resolvedHome)
        val handle = prepareKodexHome(resolvedHome)
        preparedHome = handle
        val browserAccess = LocalBrowserAccess { requested -> resolveDirectory(handle.home, requested) }
        val identity = randomBackendPassword()
        val password = randomBackendPassword()
        installBackendSignals()
        setBackendWorkingDirectory(handle.home)
        logging = initializeProcessLogging(handle.home, "Backend-${processId()}")
        val control = object : BackendControl {
            override fun status() = buildJsonObject {
                put("control_protocol", BackendControlProtocol)
                put("instance", identity)
                put("pid", processId())
                put("port", port)
                put("phase", lifecycle.state.value.name.lowercase())
                put("application_version", CurrentKodexApplicationVersion.toString())
                put("business_protocol", BackendBusinessProtocol)
                put("idle_ttl_seconds", services?.settings?.unified?.current?.backend?.daemonTtlSeconds ?: 300L)
                put("browser_grant_protocol", LocalBrowserGrantProtocol)
            }
            override fun ready() = lifecycle.state.value == BackendPhase.Ready && services != null
            override suspend fun heartbeat() = lifecycle.renew()
            override suspend fun acceptStop() = lifecycle.requestStop() == BackendStopAcceptance.Accepted
            override fun stopReplyFinished() { replyFinished.complete(Unit) }
            override suspend fun reload() {
                check(ready())
                requireNotNull(services).settings.reloadSettings()
                lifecycle.updateIdleTimeout(ttl(requireNotNull(services).settings.unified.current.backend.daemonTtlSeconds))
            }
        }
        withLocalBackendServer(password, identity, control, { admission ->
            check(control.ready()) { "The backend is not ready for business requests." }
            requireNotNull(services).register(this, allowAgentSql = admission == BackendConnectionAdmission.LocalCredential)
        }, browserAccess) { boundPort ->
            port = boundPort
            val endpoint = BackendEndpoint(identity, processId(), port, password, CurrentKodexApplicationVersion.toString())
            instance.publish(endpoint)
            published = endpoint
            val work = launch {
                try {
                    if (!ensureBundledHomeGuide(handle.home)) {
                        logger.warn { "Preserved custom or non-regular skills/kodex-home/SKILL.md; review it before following its Home instructions." }
                    }
                    withBackendServices(
                        handle.home, codexHome, agentsHome,
                        requiresPreparedHome = true,
                        createClient = createClient, createLoginClient = createLoginClient,
                        renewBackend = { lifecycle.renew() },
                        backendRenewalInterval = {
                            val value = services?.settings?.unified?.current?.backend?.daemonTtlSeconds ?: 300L
                            backendHeartbeatIntervalMillis(value).milliseconds
                        },
                        checkAdmission = {
                            check(lifecycle.state.value == BackendPhase.Ready) { "Backend business admission is closed." }
                        },
                    ) { backend ->
                        try {
                            services = backend
                            val settings = backend.settings.unified
                            lifecycle.updateIdleTimeout(ttl(settings.current.backend.daemonTtlSeconds))
                            // One consumer is launched before the Ready business-admission publication.
                            launch(start = CoroutineStart.UNDISPATCHED) {
                                collectNotificationHooks(backend.notifications, { settings.current.backend.hooks }, handle.home)
                            }
                            launch {
                                settings.backendSettings.collect { lifecycle.updateIdleTimeout(ttl(it.daemonTtlSeconds)) }
                            }
                            if (lifecycle.markReady()) awaitCancellation()
                        } catch (failure: Throwable) {
                            if (hostJob.isActive &&
                                (lifecycle.state.value == BackendPhase.Starting || lifecycle.state.value == BackendPhase.Ready)
                            ) failed = failure
                            throw failure
                        } finally {
                            // Close admission before scoped service cleanup, not only after work completion.
                            withContext(NonCancellable) {
                                coreEnded()
                                failed?.let { reportFailure(it) }
                                backend.shutdownSessions()
                            }
                        }
                    }
                } catch (failure: Throwable) {
                    if (failure !is CancellationException || hostJob.isActive &&
                        (lifecycle.state.value == BackendPhase.Starting || lifecycle.state.value == BackendPhase.Ready)
                    ) failed = failure
                    if (failure is CancellationException) throw failure
                } finally {
                    withContext(NonCancellable) {
                        coreEnded()
                        failed?.let { reportFailure(it) }
                    }
                }
            }
            val expiry = launch { lifecycle.monitorIdleExpiry() }
            val signals = launch {
                while (true) {
                    if (backendStopSignalReceived()) {
                        if (lifecycle.requestStop() == BackendStopAcceptance.Accepted) replyFinished.complete(Unit)
                        return@launch
                    }
                    delay(200)
                }
            }
            try {
                val reason = lifecycle.awaitStop()
                if (reason == BackendStopReason.Manual) withTimeoutOrNull(5.seconds) { replyFinished.await() }
            } finally {
                withContext(NonCancellable) {
                    expiry.cancelAndJoin()
                    signals.cancelAndJoin()
                    if (lifecycle.state.value == BackendPhase.Starting || lifecycle.state.value == BackendPhase.Ready) {
                        lifecycle.requestStop()
                    }
                    work.cancelAndJoin()
                    lifecycle.finishStop()
                }
            }
        }
    } catch (failure: Throwable) {
        primary = failure
        failed?.let { workFailure ->
            if (workFailure !== failure.cause &&
                generateSequence<Throwable>(workFailure) { it.cause }.take(16).none { it === failure }
            ) failure.addSuppressed(workFailure)
        }
        if (failure !is CancellationException) withContext(NonCancellable) { reportFailure(failure) }
        throw failure
    } finally {
        // Network and services have both closed before the stable lock inode is released.
        withContext(NonCancellable) {
            var cleanupPrimary = primary ?: failed
            suspend fun release(block: suspend () -> Unit) {
                try { block() }
                catch (cleanup: Throwable) {
                    val failure = cleanupPrimary
                    if (failure == null) cleanupPrimary = cleanup else if (failure !== cleanup) failure.addSuppressed(cleanup)
                }
            }
            release { logging?.close() }
            release { preparedHome?.closeAndJoin() }
            release { instance.close() }
            if (primary == null && failed == null) cleanupPrimary?.let { throw it }
        }
    }
    failed?.let { failure ->
        if (failure is CancellationException) throw IllegalStateException("Backend core cancelled: ${reason(failure)}", failure)
        throw failure
    }
}

private suspend fun verifyReadyBackend(current: BackendEndpoint): BackendEndpoint {
    check(current.businessProtocol == BackendBusinessProtocol) { "Backend protocol differs; use the management CLI." }
    current.failureReason?.let { error(it) }
    val status = backendControlRequest(current, "status")
    check(status["phase"]?.jsonPrimitive?.content == "ready") { "A locked backend cannot serve this frontend." }
    check(status.matches(current)) { "Backend status does not match the recorded instance." }
    return current
}

private fun kotlinx.serialization.json.JsonObject.matches(endpoint: BackendEndpoint): Boolean =
    get("instance")?.jsonPrimitive?.content == endpoint.instanceId &&
        get("port")?.jsonPrimitive?.intOrNull == endpoint.port &&
        get("business_protocol")?.jsonPrimitive?.content == BackendBusinessProtocol

private suspend fun <T> probe(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

public actual suspend fun discoverReadyBackend(home: Path): BackendEndpoint? {
    preflightKodexHome(home)
    return BackendInstance.discover(home)?.let { verifyReadyBackend(it) }
}

public actual suspend fun connectBackend(home: Path, port: Int): BackendEndpoint {
    require(port in 1..65535) { "Invalid backend port: $port." }
    preflightKodexHome(home)
    val current = checkNotNull(BackendInstance.discover(home)) {
        "No locked backend is running in the target Home."
    }
    check(current.port == port) {
        "Backend URL port $port does not match the recorded backend port ${current.port}."
    }
    return verifyReadyBackend(current)
}

public actual suspend fun ensureBackend(home: Path): BackendEndpoint {
    preflightKodexHome(home)
    val current = BackendInstance.discover(home)
    if (current != null) return verifyReadyBackend(current)
    SystemCoroutineFileSystem.createDirectories(home)
    val resolvedHome = SystemCoroutineFileSystem.resolve(home)
    preflightKodexHome(resolvedHome)
    // Bootstrap diagnostics are private and outside Home, including when full preparation fails.
    val startupLog = Path(SystemTemporaryDirectory, "kodex-backend-startup-${Uuid.random()}.log")
    SystemCoroutineFileSystem.writePrivateString(startupLog, "", mustCreate = true)
    try {
        val previous = probe { BackendInstance.readRecord(resolvedHome)?.instanceId }
        val launchedPid = launchIndependentBackend(resolvedHome, startupLog)
        return BackendControlClient().use { controlClient ->
            withTimeout(60.seconds) {
                while (true) {
                    val endpoint = probe { BackendInstance.discover(resolvedHome) }
                    if (endpoint == null) {
                        val ended = probe { BackendInstance.readRecord(resolvedHome) }
                        if (ended?.pid == launchedPid && ended.instanceId != previous) {
                            ended.failureReason?.let { error(it) }
                        }
                        val output = probe { SystemCoroutineFileSystem.readString(startupLog) }.orEmpty()
                        val reason = output.substringAfterLast("Unable to run Kodex [pid=$launchedPid]: ", "").trim()
                        if (reason.isNotEmpty()) error(reason)
                        if (!backendProcessAlive(launchedPid)) {
                            error(output.trim().ifEmpty { "Backend failed to start (pid=$launchedPid)." })
                        }
                    } else {
                        check(endpoint.businessProtocol == BackendBusinessProtocol) { "Backend protocol differs." }
                        if (endpoint.pid == launchedPid) endpoint.failureReason?.let { error(it) }
                        val status = probe { controlClient.request(endpoint, "status") }
                        val phase = status?.get("phase")?.jsonPrimitive?.content
                        if (status != null && phase == "ready" && status.matches(endpoint)) {
                            preflightKodexHome(resolvedHome)
                            return@withTimeout endpoint
                        }
                        if (endpoint.pid != launchedPid) {
                            check(phase != "stopping") { "Backend startup is stopping." }
                        }
                    }
                    delay(100)
                }
                @Suppress("UNREACHABLE_CODE")
                error("Backend startup failed.")
            }
        }
    } finally {
        withContext(NonCancellable) {
            runCatching { SystemCoroutineFileSystem.delete(startupLog, mustExist = false) }
        }
    }
}

private fun ttl(seconds: Long): Duration = if (seconds == -1L) Duration.INFINITE else seconds.seconds

private suspend fun rejectLiveLegacyOwner(home: Path) {
    val directory = Path(home, ".locks", "home")
    if (!SystemCoroutineFileSystem.exists(directory)) return
    for (path in SystemCoroutineFileSystem.list(directory)) {
        val pid = path.name.substringBefore('.').toLongOrNull() ?: continue
        if (pid != processId() && (path.name.endsWith(".read.lock") || path.name.endsWith(".write.lock")) && backendProcessAlive(pid)) {
            error("Another live application owns this Home. Close it before starting the backend.")
        }
    }
}

public actual suspend fun initializeProcessLogging(home: Path, processName: String): ApplicationLoggingSession {
    val directory = Path(SystemCoroutineFileSystem.resolve(home), "log")
    val existing = SystemCoroutineFileSystem.metadataOrNull(directory)
    check(if (existing == null) directory !in SystemCoroutineFileSystem.list(requireNotNull(directory.parent))
        else existing.isDirectory && SystemCoroutineFileSystem.resolve(directory) == directory
    ) {
        "The Kodex log directory is not a direct directory: $directory"
    }
    SystemCoroutineFileSystem.createDirectories(directory)
    // Kermit 2.1.0 rolls "<base>-1.log", not "<base>.1.log".
    val names = Regex("^(?:Backend|Tui|Cli)-([0-9]+)(?:-[0-9]+)?\\.log$")
    val inactive = linkedMapOf<Long, MutableList<Path>>()
    for (path in SystemCoroutineFileSystem.list(directory)) {
        val pid = names.matchEntire(path.name)?.groupValues?.get(1)?.toLongOrNull() ?: continue
        if (pid != processId() && !backendProcessAlive(pid)) inactive.getOrPut(pid) { mutableListOf() }.add(path)
    }
    val dated = inactive.entries.map { entry ->
        entry to (entry.value.map { SystemCoroutineFileSystem.fingerprintOrNull(it)?.lastModifiedAtNanoseconds ?: 0 }.maxOrNull() ?: 0)
    }.sortedByDescending { it.second }
    for ((entry, _) in dated.drop(8)) {
        entry.value.forEach { SystemCoroutineFileSystem.delete(it, mustExist = false) }
    }
    return initializeLogging(home, processName)
}
