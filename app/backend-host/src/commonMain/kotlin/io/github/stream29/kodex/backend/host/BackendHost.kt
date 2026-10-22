package io.github.stream29.kodex.backend.host

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.app.migration.CurrentKodexApplicationVersion
import io.github.stream29.kodex.app.migration.prepareKodexHome
import io.github.stream29.kodex.backend.*
import io.github.stream29.kodex.cli.notification.collectNotificationHooks
import io.github.stream29.kodex.rpc.localserver.BackendControl
import io.github.stream29.kodex.rpc.control.backendControlRequest
import io.github.stream29.kodex.rpc.localserver.withLocalBackendServer
import io.github.stream29.kodex.rpc.server.BackendServices
import io.github.stream29.kodex.rpc.server.withBackendServices
import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.logging.initializeLogging
import io.github.stream29.kodex.utils.osenvironment.processId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.io.files.Path
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

public actual suspend fun runBackendHost(
    home: Path,
    codexHome: Path,
    agentsHome: Path,
    createClient: (OpenAiAuthStore) -> OpenAiClient,
    createLoginClient: () -> OpenAiLoginClient,
): Unit = coroutineScope {
    val hostJob = currentCoroutineContext().job
    val instance = BackendInstance.acquire(home)
    val lifecycle = createBackendLifecycle()
    val replyFinished = CompletableDeferred<Unit>()
    var services: BackendServices? = null
    var failed: Throwable? = null
    var port = 0
    var published: BackendEndpoint? = null
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
        val identity = randomBackendPassword()
        val password = randomBackendPassword()
        installBackendSignals()
        setBackendWorkingDirectory(home)
        initializeProcessLogging(home, "Backend-${processId()}")
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
        withLocalBackendServer(password, identity, control, {
            check(control.ready()) { "The backend is not ready for business requests." }
            requireNotNull(services).register(this)
        }) { boundPort ->
            port = boundPort
            val endpoint = BackendEndpoint(identity, processId(), port, password, CurrentKodexApplicationVersion.toString())
            instance.publish(endpoint)
            published = endpoint
            val work = launch {
                var handle: io.github.stream29.kodex.app.migration.KodexHomeHandle? = null
                try {
                    rejectLiveLegacyOwner(home)
                    handle = prepareKodexHome(home)
                    withBackendServices(
                        home, codexHome, agentsHome,
                        createClient = createClient, createLoginClient = createLoginClient,
                        renewBackend = { lifecycle.renew() },
                        backendRenewalInterval = {
                            val value = services?.settings?.unified?.current?.backend?.daemonTtlSeconds ?: 300L
                            if (value == -1L || value >= 60) 20.seconds else (value * 1000 / 3).coerceAtLeast(100).milliseconds
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
                                collectNotificationHooks(backend.notifications, { settings.current.backend.hooks }, home)
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
                        try { handle?.closeAndJoin() }
                        catch (cleanup: Throwable) {
                            val failure = failed
                            if (failure == null) failed = cleanup else if (failure !== cleanup) failure.addSuppressed(cleanup)
                        }
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
        try { instance.close() }
        catch (cleanup: Throwable) {
            val failure = primary ?: failed
            if (failure == null) throw cleanup else if (failure !== cleanup) failure.addSuppressed(cleanup)
        }
    }
    failed?.let { failure ->
        if (failure is CancellationException) throw IllegalStateException("Backend core cancelled: ${reason(failure)}", failure)
        throw failure
    }
}

public actual suspend fun ensureBackendForTui(home: Path): BackendEndpoint {
    val current = BackendInstance.discover(home)
    if (current != null) {
        check(current.businessProtocol == BackendBusinessProtocol) { "Backend protocol differs; use the management CLI." }
        current.failureReason?.let { error(it) }
        val status = backendControlRequest(current, "status")
        check(status["phase"]?.jsonPrimitive?.content == "ready") { "A locked backend cannot serve this TUI." }
        return current
    }
    SystemCoroutineFileSystem.createDirectories(home)
    SystemCoroutineFileSystem.createDirectories(Path(home, "log"))
    val startupLog = Path(home, "log", "backend-startup.log")
    suspend fun startupOutput(): String = try {
        if (SystemCoroutineFileSystem.exists(startupLog)) SystemCoroutineFileSystem.readString(startupLog) else ""
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { "" }
    val outputOffset = startupOutput().length
    val previous = try { BackendInstance.readRecord(home)?.instanceId } catch (_: Exception) { null }
    val launchedPid = launchIndependentBackend(home)
    return withTimeout(60.seconds) {
        while (true) {
            val endpoint = try { BackendInstance.discover(home) } catch (_: Exception) { null }
            if (endpoint == null) {
                val ended = try { BackendInstance.readRecord(home) } catch (_: Exception) { null }
                if (ended?.pid == launchedPid && ended.instanceId != previous) {
                    ended.failureReason?.let { error(it) }
                }
                val output = startupOutput()
                val currentOutput = if (output.length >= outputOffset) output.substring(outputOffset) else output
                val reason = currentOutput.substringAfterLast("Unable to run Kodex [pid=$launchedPid]: ", "").trim()
                if (reason.isNotEmpty()) error(reason)
                if (!backendProcessAlive(launchedPid)) error("Backend failed to start; inspect log/backend-startup.log.")
            }
            if (endpoint != null) {
                check(endpoint.businessProtocol == BackendBusinessProtocol) { "Backend protocol differs." }
                if (endpoint.pid == launchedPid) endpoint.failureReason?.let { error(it) }
                val status = try { backendControlRequest(endpoint, "status") } catch (_: Exception) { null }
                if (status?.get("phase")?.jsonPrimitive?.content == "ready") return@withTimeout endpoint
                if (endpoint.pid != launchedPid) {
                    check(status?.get("phase")?.jsonPrimitive?.content != "stopping") { "Backend startup is stopping." }
                }
            }
            delay(100)
        }
        @Suppress("UNREACHABLE_CODE")
        error("Backend startup failed.")
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

public actual suspend fun initializeProcessLogging(home: Path, processName: String) {
    val directory = Path(home, "log")
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
    if (processName.startsWith("Backend-")) {
        val startup = Path(directory, "backend-startup.log")
        if ((SystemCoroutineFileSystem.fingerprintOrNull(startup)?.size ?: 0) > 10 * 1024 * 1024) {
            SystemCoroutineFileSystem.writeString(startup, "")
        }
    }
    initializeLogging(home, processName)
}
