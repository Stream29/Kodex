package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.oshai.kotlinlogging.DirectLoggerFactory
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import io.github.stream29.kodex.app.migration.prepareKodexHome
import io.github.stream29.kodex.backend.BackendEndpoint
import io.github.stream29.kodex.backend.BackendInstance
import io.github.stream29.kodex.backend.host.runBackendHost
import io.github.stream29.kodex.backend.randomBackendPassword
import io.github.stream29.kodex.backend.setBackendWorkingDirectory
import io.github.stream29.kodex.rpc.control.backendControlRequest
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.osenvironment.processId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

val cliFailureLoggingTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("thin CLI selects the direct logger before disabling console logging") {
        val previousFactory = KotlinLoggingConfiguration.loggerFactory
        val previousStartup = KotlinLoggingConfiguration.logStartupMessage
        val previousLevel = if (previousFactory === DirectLoggerFactory) {
            KotlinLoggingConfiguration.direct.logLevel
        } else null
        try {
            initializeCliLogging()
            assertSame(DirectLoggerFactory, KotlinLoggingConfiguration.loggerFactory)
            assertFalse(KotlinLoggingConfiguration.logStartupMessage)
            assertEquals(Level.OFF, KotlinLoggingConfiguration.direct.logLevel)
        } finally {
            if (previousLevel != null) KotlinLoggingConfiguration.direct.logLevel = previousLevel
            KotlinLoggingConfiguration.loggerFactory = previousFactory
            KotlinLoggingConfiguration.logStartupMessage = previousStartup
        }
    }

    test("early errors and detached coroutine failures do not create logs in fresh or old Homes") {
        val home = Path(SystemTemporaryDirectory, "cli-failure-logging-${Random.nextLong()}")
        val failure = IllegalStateException("outer failure marker", IllegalArgumentException("cause marker"))
        val fs = SystemCoroutineFileSystem
        val previousLevel = KotlinLoggingConfiguration.direct.logLevel
        try {
            KotlinLoggingConfiguration.direct.logLevel = Level.OFF
            for (old in listOf(false, true)) {
                val version = Path(home, "version.json")
                if (old) {
                    fs.createDirectories(home, mustCreate = true)
                    fs.writeString(version, "\"0.7.5\"")
                }
                val messages = mutableListOf<String>()
                val result = runCliSafely(false, ::processId, { logCliFailure(it, home) }, messages::add) { throw failure }
                assertEquals(1, result)
                assertEquals(listOf("Unable to run Kodex: outer failure marker"), messages)
                val stderr = mutableListOf<String>()
                createCliCoroutineExceptionHandler(home = { home }, stderrOutput = stderr::add)
                    .handleException(kotlin.coroutines.EmptyCoroutineContext, failure)
                assertEquals(listOf("Unhandled CLI coroutine failure: outer failure marker"), stderr)
                assertFalse(fs.exists(Path(home, "log")))
                if (old) {
                    assertEquals("\"0.7.5\"", fs.readString(version))
                    assertEquals(listOf("version.json"), fs.list(home).map { it.name })
                } else {
                    assertFalse(fs.exists(home))
                }
            }
        } finally {
            KotlinLoggingConfiguration.direct.logLevel = previousLevel
            fs.delete(Path(home, "version.json"), mustExist = false)
            fs.delete(home, mustExist = false)
        }
    }

    test("backend management reports a fresh Home as not running and refuses all old Home commands") {
        val fs = SystemCoroutineFileSystem
        val home = Path(SystemTemporaryDirectory, "cli-control-home-${Random.nextLong()}")
        val version = Path(home, "version.json")
        try {
            val output = mutableListOf<String>()
            assertEquals(0, runBackendControlCli(BackendControlCommand.Status, home, output::add))
            assertEquals(listOf("""{"phase":"not_running"}"""), output)
            assertFalse(fs.exists(home))
            fs.createDirectories(home, mustCreate = true)
            fs.writeString(version, "\"0.7.5\"")
            for (command in BackendControlCommand.entries) {
                output.clear()
                assertEquals(1, runBackendControlCli(command, home, output::add))
                assertContains(output.single(), "incompatible_kodex_home")
                assertContains(output.single(), "different, empty Kodex Home")
                assertEquals(listOf("version.json"), fs.list(home).map { it.name })
                assertEquals("\"0.7.5\"", fs.readString(version))
            }
        } finally {
            fs.delete(version, mustExist = false)
            fs.delete(home, mustExist = false)
        }
    }

    test("resolveFrontendBackend routes default and explicit backend URLs with optional backend Home") {
        val frontendHome = Path(SystemTemporaryDirectory, "kodex-cli-frontend-${Random.nextLong()}")
        val backendHome = Path(SystemTemporaryDirectory, "kodex-cli-backend-${Random.nextLong()}")
        SystemCoroutineFileSystem.createDirectories(backendHome)
        try {
            val expected = BackendEndpoint("instance-1", 42, 18080, "a".repeat(64), "0.6.0")
            var ensured = 0
            var connected = 0
            val defaultResolved = resolveFrontendBackend(
                frontendHome,
                target = null,
                ensure = { home ->
                    ensured++
                    assertEquals(frontendHome, home)
                    expected
                },
                connect = { _, _ -> fail("Default target must not call connectBackend") },
            )
            assertEquals(frontendHome to expected, defaultResolved)
            assertEquals(1, ensured)

            val sameHomeResolved = resolveFrontendBackend(
                frontendHome,
                target = CliBackendTarget(port = 18080),
                ensure = { fail("Explicit --backend-url must never auto-start or ensure a backend") },
                connect = { home, port ->
                    connected++
                    assertEquals(frontendHome, home)
                    assertEquals(18080, port)
                    expected
                },
            )
            assertEquals(frontendHome to expected, sameHomeResolved)
            assertEquals(1, connected)

            val resolvedBackendHome = SystemCoroutineFileSystem.resolve(backendHome)
            val separateHomeResolved = resolveFrontendBackend(
                frontendHome,
                target = CliBackendTarget(port = 18080, backendHome = backendHome.toString()),
                ensure = { fail("Explicit --backend-url with --backend-home must never auto-start a backend") },
                connect = { home, port ->
                    connected++
                    assertEquals(resolvedBackendHome, home)
                    assertEquals(18080, port)
                    expected
                },
            )
            assertEquals(resolvedBackendHome to expected, separateHomeResolved)
            assertEquals(2, connected)
            assertFalse(SystemCoroutineFileSystem.exists(frontendHome))
        } finally {
            SystemCoroutineFileSystem.delete(backendHome, mustExist = false)
        }
    }

    test("web connects to the target backend Home without a frontend record and leaves backend running") {
        val originalCwd = SystemCoroutineFileSystem.resolve(Path("."))
        val root = Path(SystemTemporaryDirectory, "kodex-cli-web-target-${Random.nextLong()}")
        val frontendHome = Path(root, "frontend-home")
        val backendHome = Path(root, "backend-home")
        val launchDir = Path(root, "project")
        SystemCoroutineFileSystem.createDirectories(frontendHome)
        SystemCoroutineFileSystem.createDirectories(backendHome)
        SystemCoroutineFileSystem.createDirectories(launchDir)
        SystemCoroutineFileSystem.writeString(Path(frontendHome, "version.json"), "\"0.7.5\"")
        kotlinx.coroutines.coroutineScope { prepareKodexHome(backendHome).closeAndJoin() }
        SystemCoroutineFileSystem.writeString(
            Path(backendHome, "settings.yml"),
            "backend:\n  daemon_ttl_seconds: -1\n  new_session:\n    model: fixture\n  session_title:\n    enabled: false\nfrontend: {}\n",
        )
        val resolvedLaunch = SystemCoroutineFileSystem.resolve(launchDir).toString()
        try {
            kotlinx.coroutines.coroutineScope {
                val backendJob = launch {
                    runBackendHost(
                        backendHome,
                        Path(backendHome, "codex"),
                        Path(backendHome, "agents"),
                    )
                }
                var liveEndpoint: BackendEndpoint? = null
                try {
                    withTimeout(15.seconds) {
                        while (true) {
                            val found = try { BackendInstance.discover(backendHome) } catch (_: Exception) { null }
                            if (found != null) {
                                val status = try { backendControlRequest(found, "status") } catch (_: Exception) { null }
                                if (status?.get("phase")?.jsonPrimitive?.content == "ready") {
                                    liveEndpoint = found
                                    break
                                }
                            }
                            delay(50)
                        }
                    }
                    val targetEndpoint = assertNotNull(liveEndpoint)
                    val (resolvedHome, connectedEndpoint) = resolveFrontendBackend(
                        frontendHome,
                        CliBackendTarget(port = targetEndpoint.port, backendHome = backendHome.toString()),
                    )
                    assertEquals(SystemCoroutineFileSystem.resolve(backendHome), resolvedHome)
                    assertEquals(targetEndpoint.instanceId, connectedEndpoint.instanceId)
                    initializeTuiLoggingForBackendHome(frontendHome, resolvedHome)
                    assertFalse(SystemCoroutineFileSystem.exists(Path(frontendHome, "log")))
                    assertEquals("\"0.7.5\"", SystemCoroutineFileSystem.readString(Path(frontendHome, "version.json")))

                    logCliFailure(IllegalStateException("outer failure marker", IllegalArgumentException("cause marker")), backendHome)
                    val failureLog = SystemCoroutineFileSystem.list(Path(backendHome, "log"))
                        .single { it.name.matches(Regex("Cli-${processId()}-[0-9]+\\.log")) }
                    val diagnostic = SystemCoroutineFileSystem.readString(failureLog)
                    assertContains(diagnostic, "outer failure marker")
                    assertContains(diagnostic, "cause marker")

                    withLocalFrontendHost(
                        connectedEndpoint,
                        resolvedLaunch,
                        { if (it == "index.html") Buffer().apply { writeString("<!doctype html>") } else null },
                        ::randomBackendPassword,
                    ) { launchUrl ->
                        assertContains(launchUrl, "http://127.0.0.1:")
                        assertFalse(launchUrl.contains(connectedEndpoint.password))
                    }
                    assertEquals("ready", backendControlRequest(targetEndpoint, "status")["phase"]?.jsonPrimitive?.content)

                    assertFalse(SystemCoroutineFileSystem.exists(Path(frontendHome, ".locks", "backend")))
                    assertFalse(SystemCoroutineFileSystem.exists(Path(frontendHome, ".locks", "frontend")))
                    assertFalse(SystemCoroutineFileSystem.exists(Path(backendHome, ".locks", "frontend")))
                } finally {
                    withContext(NonCancellable) {
                        if (backendJob.isActive && liveEndpoint != null) {
                            runCatching { backendControlRequest(requireNotNull(liveEndpoint), "stop") }
                        }
                        withTimeoutOrNull(10.seconds) { backendJob.join() } ?: backendJob.cancel()
                    }
                }
            }
        } finally {
            // The in-process backend changes the process cwd. Restore it before removing that directory.
            setBackendWorkingDirectory(originalCwd)
            suspend fun removeTree(path: Path) {
                val meta = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
                if (meta.isDirectory) SystemCoroutineFileSystem.list(path).forEach { removeTree(it) }
                SystemCoroutineFileSystem.delete(path, mustExist = false)
            }
            removeTree(root)
        }
    }
}
