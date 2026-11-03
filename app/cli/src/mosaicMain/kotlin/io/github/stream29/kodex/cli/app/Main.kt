package io.github.stream29.kodex.cli.app

import dev.kauri.framework.openEmbeddedResource
import io.github.stream29.kodex.backend.randomBackendPassword
import io.github.stream29.kodex.backend.backendProcessAlive
import io.github.stream29.kodex.backend.BackendEndpoint
import io.github.stream29.kodex.backend.BackendInstance
import io.github.stream29.kodex.backend.LocalFrontendHostJson
import io.github.stream29.kodex.backend.LocalFrontendPairingOffer
import io.github.stream29.kodex.backend.LocalFrontendHostMaxFrameBytes
import io.github.stream29.kodex.backend.exitKodexProcess
import io.github.stream29.kodex.backend.installBackendSignals
import io.github.stream29.kodex.backend.backendStopSignalReceived
import io.github.stream29.kodex.backend.host.connectBackend
import io.github.stream29.kodex.backend.host.ensureBackend
import io.github.stream29.kodex.backend.host.runBackendHost
import io.github.stream29.kodex.rpc.control.backendControlRequest
import io.github.stream29.kodex.rpc.local.withLocalRpcClient
import io.github.stream29.kodex.utils.kodexhome.KodexHome
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.writeBytes
import io.github.stream29.kodex.utils.osenvironment.processId
import kotlinx.coroutines.*
import kotlinx.io.files.Path

public actual fun main(args: Array<String>) {
    val result = dispatchCli(args.asList(), ::printCliMessage, ::runInvocation)
    if (result != 0) exitKodexProcess(result)
}

private fun runInvocation(invocation: CliInvocation): Int {
    var result = 0
    return runCliSafely(
        backendStart = invocation is CliInvocation.BackendStart,
        processId = ::processId,
        reportFailure = { logCliFailure(it, KodexHome) },
        output = ::println,
    ) {
        runBlocking(CliCoroutineExceptionLogger) {
            val home = KodexHome
            when (invocation) {
                is CliInvocation.Web -> {
                    val cwd = SystemCoroutineFileSystem.resolve(Path("."))
                    val (_, endpoint) = resolveFrontendBackend(home, invocation.backend)
                    val origin = invocation.origin
                    if (origin == null) {
                        withLocalFrontendHost(
                            endpoint, cwd.toString(), ::openEmbeddedResource, ::randomBackendPassword,
                        ) { url ->
                            installBackendSignals()
                            result = runLocalWebFrontend(
                                url, window = false,
                                stopRequested = ::backendStopSignalReceived, engine = LocalAppEngine.Default,
                            )
                        }
                    } else {
                        result = runWebPairingFrontend(home, endpoint, cwd.toString(), origin)
                    }
                }
                is CliInvocation.App -> {
                    val engine = resolveLocalAppEngine(when (invocation.engine) {
                        CliAppEngine.Default -> LocalAppEngine.Default
                        CliAppEngine.Chrome -> LocalAppEngine.Chrome
                        CliAppEngine.System -> LocalAppEngine.System
                    })
                    val cwd = SystemCoroutineFileSystem.resolve(Path("."))
                    withLocalFrontendHost(ensureBackend(home), cwd.toString(),
                        ::openEmbeddedResource, ::randomBackendPassword) { url ->
                        installBackendSignals()
                        result = runLocalWebFrontend(
                            url, window = true,
                            stopRequested = ::backendStopSignalReceived, engine = engine,
                        )
                    }
                }
                is CliInvocation.BackendStart -> runBackendHost(
                    home, browserOrigins = invocation.browserOrigins,
                )
                is CliInvocation.BackendControl -> {
                    try {
                        val endpoint = BackendInstance.discover(home)
                        if (endpoint == null) {
                            println("""{"phase":"not_running"}""")
                            if (invocation.command != BackendControlCommand.Status) result = 1
                        } else println(backendControlRequest(endpoint, invocation.command.commandName))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        println("""{"phase":"unavailable","error":"backend_cannot_serve_command"}""")
                        result = 1
                    }
                }
                is CliInvocation.Tui -> {
                    val cwd = SystemCoroutineFileSystem.resolve(Path("."))
                    val (backendHome, endpoint) = resolveFrontendBackend(home, invocation.backend)
                    initializeTuiLogging(home, ::backendProcessAlive)
                    withLocalRpcClient(backendHome, endpoint) { connection ->
                        withFrontendApplication(connection, cwd, NativeFrontendHost) { application ->
                            runTuiApplication(application)
                        }
                    }
                }
            }
        }
        result
    }
}

internal suspend fun resolveFrontendBackend(
    home: Path,
    target: CliBackendTarget?,
    ensure: suspend (Path) -> BackendEndpoint = ::ensureBackend,
    connect: suspend (Path, Int) -> BackendEndpoint = ::connectBackend,
): Pair<Path, BackendEndpoint> {
    if (target == null) return home to ensure(home)
    val rawHome = target.backendHome
    val backendHome = if (rawHome == null) {
        home
    } else {
        val candidate = Path(rawHome)
        if (SystemCoroutineFileSystem.exists(candidate)) SystemCoroutineFileSystem.resolve(candidate) else candidate
    }
    return backendHome to connect(backendHome, target.port)
}

internal suspend fun runWebPairingFrontend(
    home: Path,
    endpoint: BackendEndpoint,
    cwd: String,
    origin: String,
    newSecret: () -> String = ::randomBackendPassword,
    output: (String) -> Unit = ::println,
    awaitStop: suspend (LocalFrontendPairingOffer, Path) -> Int = { _, _ ->
        installBackendSignals()
        while (!backendStopSignalReceived()) delay(100)
        130
    },
): Int = withLocalFrontendPairingHost(
    endpoint, cwd, origin, newSecret,
) { offer ->
    val directory = Path(home, ".locks", "frontend")
    val record = Path(directory, "pairing-${offer.hostInstanceId}.json")
    SystemCoroutineFileSystem.createDirectories(directory)
    val bytes = LocalFrontendHostJson.encodeToString(
        LocalFrontendPairingOffer.serializer(), offer,
    ).encodeToByteArray()
    check(bytes.size <= LocalFrontendHostMaxFrameBytes) { "Scoped pairing offer exceeds the host limit." }
    var ownsRecord = false
    var primary: Throwable? = null
    try {
        SystemCoroutineFileSystem.useSink(record, mustCreate = true) { sink ->
            ownsRecord = true
            SystemCoroutineFileSystem.protectPrivateFile(record)
            sink.writeBytes(bytes)
            sink.flush()
        }
        output("Select this scoped pairing record within 60 seconds: $record")
        output("Trusted frontend Origin: $origin. Keep this frontend server running; Ctrl+C closes its resources, not backend work.")
        awaitStop(offer, record)
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        if (ownsRecord) withContext(NonCancellable) {
            try { SystemCoroutineFileSystem.delete(record, mustExist = false) }
            catch (cleanup: Throwable) {
                if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
            }
        }
    }
}
