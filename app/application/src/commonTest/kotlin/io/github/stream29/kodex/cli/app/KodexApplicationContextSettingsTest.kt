package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import de.infix.testBalloon.framework.core.TestCompartment
import io.github.stream29.kodex.app.migration.*
import io.github.stream29.kodex.app.test.deleteTestDirectory
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.cli.settings.SidebarContent
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem as Fs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.*

val kodexApplicationContextSettingsTest by testSuite(compartment = { TestCompartment.RealTime }) {
    for (phase in ApplicationStartupPhase.entries) {
        test("startup failure at $phase releases clients and the prepared Home") {
            coroutineScope {
                val home = Path(SystemTemporaryDirectory, "kodex-cutover-failure-${Random.nextLong()}")
                val error = IllegalArgumentException("failed at $phase")
                var created = 0
                var closed = 0
                try {
                    val handle = prepareKodexHome(home)
                    val caught = assertFailsWith<IllegalArgumentException> {
                        withKodexApplication(
                            handle, home, Path(home, "codex"), Path(home, "agents"),
                            createClient = {
                                created++
                                object : OpenAiClient by applicationClient() {
                                    override fun close() { closed++ }
                                }
                            },
                            createLoginClient = { ApplicationLoginClient },
                            onPhase = { if (it == phase) throw error },
                        ) { error("Renderer must not start.") }
                    }
                    assertEquals(error.message, caught.message)
                    assertEquals(created, closed)
                    assertHomeReleased(home)
                    prepareKodexHome(home).closeAndJoin()
                } finally { withContext(NonCancellable) { deleteTestDirectory(home) } }
            }
        }
    }
    test("normal renderer return closes the real host and preserves the result") {
        coroutineScope {
            val home = Path(SystemTemporaryDirectory, "kodex-cutover-return-${Random.nextLong()}")
            try {
                val handle = prepareKodexHome(home)
                val value = withKodexApplication(
                    handle, home, Path(home, "missing-codex"), Path(home, "missing-agents"),
                    createClient = { applicationClient() }, createLoginClient = { ApplicationLoginClient },
                ) {
                    assertFalse(Fs.exists(Path(home, "missing-agents")))
                    assertEquals(Fs.resolve(home), it.viewModel.newSession.settings.value.cwd)
                    "rendered"
                }
                assertEquals("rendered", value)
                assertEquals("\"0.4.15\"", Fs.readString(Path(home, "version.json")))
                assertHomeReleased(home)
            } finally { deleteTestDirectory(home) }
        }
    }
    test("prepared custom Home migrates legacy settings before the production frontend reads them") {
        coroutineScope {
            val home = Path(SystemTemporaryDirectory, "kodex-cutover-upgrade-${Random.nextLong()}")
            try {
                Fs.createDirectories(home)
                Fs.writeString(Path(home, "version.json"), "\"0.4.6\"")
                Fs.writeString(Path(home, "settings.yml"), """
                    new_line_key: enter
                    new_session: {model: fixture-migrated-model}
                    session_title: {enabled: false}
                    sidebars: {left: none, right: history_index, left_width: 45, right_width: 60}
                    hooks: {discarded: {type: stop, command: echo should-never-run}}
                """.trimIndent())
                val handle = prepareKodexHome(home)
                withKodexApplication(
                    handle, home, Path(home, "codex"), Path(home, "agents"), applicationWidth = 120,
                    createClient = { applicationClient() }, createLoginClient = { ApplicationLoginClient },
                ) {
                    assertTrue(Fs.exists(Path(home, "settings.yml")))
                    assertFalse(Fs.exists(Path(home, "settings.backend.yml")))
                    assertFalse(Fs.exists(Path(home, "settings.frontend.cli.yml")))
                    assertEquals("fixture-migrated-model", it.viewModel.newSession.settings.value.model.value)
                    assertEquals(NewLineKey.Enter, it.newLineKey.value)
                    assertEquals(SidebarContent.None, it.sidebarSettings.state.value.left)
                    assertEquals(SidebarContent.HistoryIndex, it.sidebarSettings.state.value.right)
                    assertEquals(30, it.sidebarSettings.state.value.leftWidth)
                    val encoded = Fs.readString(Path(home, "settings.yml"))
                    assertFalse("should-never-run" in encoded)
                    assertFalse("left_width" in encoded)
                    // The migration assertion uses a deliberately custom model; select the
                    // mock catalog's model before starting the first real backend turn.
                    it.viewModel.newSession.updateModel(OpenAiModelId("test-model"))
                    it.viewModel.newSession.settings.first { value -> value.model == OpenAiModelId("test-model") }
                    val session = it.viewModel.submitTestSession()
                    assertTrue(session.sessionIndex >= 0)
                    assertTrue(Fs.exists(Path(home, "sessions")))
                }
                assertEquals("\"0.4.15\"", Fs.readString(Path(home, "version.json")))
                assertHomeReleased(home)
            } finally { withContext(NonCancellable) { deleteTestDirectory(home) } }
        }
    }
    for (checkpoint in listOf("0.4.7", "0.4.10")) {
        test("legacy checkpoint $checkpoint migrates before another prepared Home reader stays alive") {
            coroutineScope {
                val home = Path(SystemTemporaryDirectory, "kodex-cutover-checkpoint-${Random.nextLong()}")
                try {
                    Fs.createDirectories(home)
                    Fs.writeString(Path(home, "version.json"), "\"$checkpoint\"")
                    val otherReader = prepareKodexHome(home)
                    try {
                        val handle = prepareKodexHome(home)
                        assertEquals(MigrationVersion("0.4.15"), handle.version)
                        val value = withKodexApplication(
                            handle, home, Path(home, "codex"), Path(home, "agents"),
                            createClient = { applicationClient() },
                            createLoginClient = { ApplicationLoginClient },
                        ) { "rendered" }
                        assertEquals("rendered", value)
                        assertEquals("\"0.4.15\"", Fs.readString(Path(home, "version.json")))
                        assertTrue(Fs.list(Path(home, ".locks/home")).any { it.name.endsWith(".read.lock") })
                    } finally {
                        otherReader.closeAndJoin()
                    }
                    assertHomeReleased(home)
                } finally {
                    withContext(NonCancellable) { deleteTestDirectory(home) }
                }
            }
        }
    }
    test("cancelling the renderer releases Home before the host job completes") {
        // Exercise immediate host cancellation with independently prepared real Homes.
        // The gated subscription regression in RpcSettingsTest covers the exact late-error order.
        repeat(8) {
            coroutineScope {
                val home = Path(SystemTemporaryDirectory, "kodex-cutover-cancel-${Random.nextLong()}")
                val entered = CompletableDeferred<Unit>()
                try {
                    val work = launch {
                        val handle = prepareKodexHome(home)
                        withKodexApplication(
                            handle, home, Path(home, "codex"), Path(home, "agents"),
                            createClient = { applicationClient() }, createLoginClient = { ApplicationLoginClient },
                        ) { entered.complete(Unit); awaitCancellation() }
                    }
                    entered.await()
                    work.cancelAndJoin()
                    assertHomeReleased(home)
                    prepareKodexHome(home).closeAndJoin()
                } finally { deleteTestDirectory(home) }
            }
        }
    }
    for (rendererFails in listOf(false, true)) {
        test("client cleanup failure preserves renderer failure=$rendererFails and releases Home") {
            coroutineScope {
                val home = Path(SystemTemporaryDirectory, "kodex-cutover-cleanup-${Random.nextLong()}")
                var closed = false
                try {
                    val handle = prepareKodexHome(home)
                    val caught = assertFailsWith<IllegalStateException> {
                        withKodexApplication(
                            handle, home, Path(home, "codex"), Path(home, "agents"),
                            createClient = {
                                object : OpenAiClient by applicationClient() {
                                    override fun close() {
                                        closed = true
                                        error("cleanup failure")
                                    }
                                }
                            },
                            createLoginClient = { ApplicationLoginClient },
                        ) { if (rendererFails) throw RendererFailure(Unit) }
                    }
                    assertTrue(closed)
                    assertEquals(if (rendererFails) "renderer failure" else "cleanup failure", caught.message)
                    if (rendererFails) {
                        fun containsCleanup(error: Throwable): Boolean =
                            error.message == "cleanup failure" ||
                                error.suppressedExceptions.any(::containsCleanup) ||
                                error.cause?.takeIf { it !== error }?.let(::containsCleanup) == true
                        assertTrue(containsCleanup(caught))
                    }
                    assertHomeReleased(home)
                    prepareKodexHome(home).closeAndJoin()
                } finally { withContext(NonCancellable) { deleteTestDirectory(home) } }
            }
        }
    }
}

// Retain the actual Throwable for suppressed-failure assertions: debug stack-trace
// recovery may otherwise copy IllegalStateException at each coroutine boundary.
private class RendererFailure(val marker: Unit) : IllegalStateException("renderer failure")

private suspend fun assertHomeReleased(home: Path) {
    val directory = Path(home, ".locks/home")
    if (Fs.exists(directory)) assertTrue(Fs.list(directory).none { it.name.endsWith(".read.lock") })
}
