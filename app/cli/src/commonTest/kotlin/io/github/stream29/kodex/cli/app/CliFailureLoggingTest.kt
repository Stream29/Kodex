package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.oshai.kotlinlogging.DirectLoggerFactory
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import io.github.stream29.kodex.backend.BackendEndpoint
import io.github.stream29.kodex.backend.BackendInstance
import io.github.stream29.kodex.backend.host.runBackendHost
import io.github.stream29.kodex.backend.randomBackendPassword
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

    test("initialized TUI and early failure both write the throwable to their process log") {
        // The isolated test process retains its rolling writers until exit.
        val home = Path(SystemTemporaryDirectory, "cli-failure-logging-${Random.nextLong()}")
        val failure = IllegalStateException("outer failure marker", IllegalArgumentException("cause marker"))
        initializeTuiLogging(home) { false }
        val messages = mutableListOf<String>()
        val result = runCliSafely(false, ::processId, { logCliFailure(it, home) }, { messages += it }) {
            throw failure
        }
        assertEquals(1, result)
        assertEquals(listOf("Unable to run Kodex: outer failure marker"), messages)

        suspend fun readFailureLog(base: Path): String {
            val files = SystemCoroutineFileSystem.list(Path(base, "log"))
                .filter { it.name.matches(Regex("Cli-${processId()}-[0-9]+\\.log")) }
            assertEquals(1, files.size)
            return SystemCoroutineFileSystem.readString(files.single())
        }
        val tuiLog = readFailureLog(home)
        assertContains(tuiLog, "CLI invocation failed")
        assertContains(tuiLog, "IllegalStateException")
        assertContains(tuiLog, "outer failure marker")
        assertContains(tuiLog, "IllegalArgumentException")
        assertContains(tuiLog, "cause marker")

        val earlyHome = Path(home, "early")
        logCliFailure(failure, earlyHome)
        val cliLog = readFailureLog(earlyHome)
        assertContains(cliLog, "IllegalStateException")
        assertContains(cliLog, "IllegalArgumentException")

        val unavailableHome = Path(home, "not-a-directory")
        SystemCoroutineFileSystem.writeString(unavailableHome, "test fixture")
        val fallback = mutableListOf<String>()
        val fallbackCode = runCliSafely(false, ::processId, { logCliFailure(it, unavailableHome) }, { fallback += it }) {
            throw failure
        }
        assertEquals(1, fallbackCode)
        assertEquals(listOf("Unable to run Kodex: outer failure marker"), fallback)

        val previousLevel = KotlinLoggingConfiguration.direct.logLevel
        try {
            KotlinLoggingConfiguration.direct.logLevel = Level.OFF
            val nonTuiHome = Path(home, "non-tui-coroutine")
            val stderrMessages = mutableListOf<String>()
            val handler = createCliCoroutineExceptionHandler(
                home = { nonTuiHome },
                stderrOutput = stderrMessages::add,
            )
            handler.handleException(kotlin.coroutines.EmptyCoroutineContext, failure)
            val coroutineLog = readFailureLog(nonTuiHome)
            assertContains(coroutineLog, "outer failure marker")
            assertEquals(listOf("Unhandled CLI coroutine failure: outer failure marker"), stderrMessages)
        } finally {
            KotlinLoggingConfiguration.direct.logLevel = previousLevel
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
        val root = Path(SystemTemporaryDirectory, "kodex-cli-web-target-${Random.nextLong()}")
        val frontendHome = Path(root, "frontend-home")
        val backendHome = Path(root, "backend-home")
        val launchDir = Path(root, "project")
        SystemCoroutineFileSystem.createDirectories(frontendHome)
        SystemCoroutineFileSystem.createDirectories(backendHome)
        SystemCoroutineFileSystem.createDirectories(launchDir)
        SystemCoroutineFileSystem.writeString(Path(backendHome, "version.json"), "\"0.4.15\"")
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
            suspend fun removeTree(path: Path) {
                val meta = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
                if (meta.isDirectory) SystemCoroutineFileSystem.list(path).forEach { removeTree(it) }
                SystemCoroutineFileSystem.delete(path, mustExist = false)
            }
            removeTree(root)
        }
    }
}
