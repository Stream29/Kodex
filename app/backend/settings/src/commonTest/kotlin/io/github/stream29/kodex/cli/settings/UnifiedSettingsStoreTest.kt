package io.github.stream29.kodex.cli.settings

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentcontext.contract.AgentContextSourceSettings
import io.github.stream29.kodex.rpc.models.BackendSettings
import io.github.stream29.kodex.rpc.models.CliFrontendSettings
import io.github.stream29.kodex.rpc.models.NotificationHook
import io.github.stream29.kodex.rpc.models.NotificationHookType
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellType
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val unifiedSettingsStoreTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("read missing file has no side effects and writes preserve the other section") {
        withSettingsDirectory { directory ->
            val store = UnifiedSettingsStore.open(directory, backendDefaults())
            assertFalse(SystemCoroutineFileSystem.exists(store.path))
            val original = store.current
            val changed = original.frontend.copy(newLineKey = NewLineKey.Enter)
            assertTrue(store.compareAndSetFrontend(original.frontend, changed))
            assertEquals(original.backend, store.current.backend)
            store.backendStore.update { it.copy(daemonTtlSeconds = -1) }
            assertEquals(changed, store.current.frontend)
            val reopened = UnifiedSettingsStore.open(directory, backendDefaults())
            assertEquals(store.current, reopened.current)
        }
    }
    test("unreloaded manual disk edits do not enter runtime and may be overwritten") {
        withSettingsDirectory { directory ->
            val store = UnifiedSettingsStore.open(directory, backendDefaults())
            SystemCoroutineFileSystem.createDirectories(directory)
            SystemCoroutineFileSystem.writeString(store.path, "backend:\n  daemon_ttl_seconds: 9\nfrontend: {}\n")
            assertEquals(300L, store.current.backend.daemonTtlSeconds)
            store.backendStore.update { it.copy(daemonTtlSeconds = -1) }
            assertEquals(-1L, store.readCandidate().backend.daemonTtlSeconds)
        }
    }
    test("reload validation and concurrent runtime changes cannot partially publish") {
        withSettingsDirectory { directory ->
            val store = UnifiedSettingsStore.open(directory, backendDefaults())
            SystemCoroutineFileSystem.createDirectories(directory)
            SystemCoroutineFileSystem.writeString(store.path, "backend:\n  daemon_ttl_seconds: 9\nfrontend:\n  new_line_key: enter\n")
            val expected = store.current
            val candidate = store.readCandidate()
            assertFailsWith<IllegalArgumentException> {
                store.acceptReload(expected, candidate) { _, _ -> throw IllegalArgumentException("rejected") }
            }
            assertEquals(expected, store.current)
            store.backendStore.update { it.copy(daemonTtlSeconds = -1) }
            assertFalse(store.acceptReload(expected, candidate) { _, _ -> })
            val latest = store.current
            assertTrue(store.acceptReload(latest, candidate) { _, _ -> })
            assertEquals(9L, store.current.backend.daemonTtlSeconds)
            assertEquals(NewLineKey.Enter, store.current.frontend.newLineKey)
        }
    }
    test("invalid file and invalid frontend hook types never reset runtime state") {
        withSettingsDirectory { directory ->
            val store = UnifiedSettingsStore.open(directory, backendDefaults())
            val old = store.current
            val bad = CliFrontendSettings(hooks = listOf(NotificationHook("bad", setOf(NotificationHookType.StopAssistantMessage), "cmd")))
            assertFailsWith<IllegalArgumentException> { store.compareAndSetFrontend(old.frontend, bad) }
            SystemCoroutineFileSystem.createDirectories(directory)
            SystemCoroutineFileSystem.writeString(store.path, "backend:\n  daemon_ttl_seconds: 0\nfrontend: {}\n")
            assertFailsWith<IllegalArgumentException> { store.readCandidate() }
            assertEquals(old, store.current)
        }
    }
}

internal class FaultingSettingsFileSystem : CoroutineFileSystem by SystemCoroutineFileSystem {
    var afterWrite: suspend () -> Unit = {}
    var beforeMove: suspend () -> Unit = {}
    var afterMove: suspend () -> Unit = {}
    var afterDelete: suspend () -> Unit = {}

    override suspend fun writePrivateString(path: Path, content: String, mustCreate: Boolean) {
        assertTrue(mustCreate)
        SystemCoroutineFileSystem.writePrivateString(path, content, mustCreate)
        afterWrite()
    }

    override suspend fun atomicMove(source: Path, destination: Path) {
        beforeMove()
        SystemCoroutineFileSystem.atomicMove(source, destination)
        afterMove()
    }

    override suspend fun delete(path: Path, mustExist: Boolean) {
        currentCoroutineContext().ensureActive()
        SystemCoroutineFileSystem.delete(path, mustExist)
        afterDelete()
    }
}

internal fun backendDefaults(): BackendSettings = BackendSettings(
    authSource = KodexAuthSource.Codex,
    shell = Shell(ShellType.Sh, Path("/bin/sh")),
    contextSources = AgentContextSourceSettings(),
    newSession = KodexNewSessionSettings(),
    sessionTitle = SessionTitleSettings(),
    mcpServers = emptyMap(),
)

internal suspend fun writeFixture(path: Path, text: String) {
    SystemCoroutineFileSystem.createDirectories(requireNotNull(path.parent))
    SystemCoroutineFileSystem.writeString(path, text)
}

internal suspend fun withSettingsDirectory(block: suspend (Path) -> Unit) {
    val root = Path(SystemTemporaryDirectory, "kodex-settings-${Random.nextLong()}")
    SystemCoroutineFileSystem.createDirectories(root, mustCreate = true)
    try {
        block(Path(root, "settings"))
    } finally {
        withContext(NonCancellable) {
            withTimeout(10.seconds) { deleteSettingsDirectory(root) }
        }
    }
}

private suspend fun deleteSettingsDirectory(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { deleteSettingsDirectory(it) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
