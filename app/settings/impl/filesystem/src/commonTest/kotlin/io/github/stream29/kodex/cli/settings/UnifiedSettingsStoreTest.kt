package io.github.stream29.kodex.cli.settings

import de.infix.testBalloon.framework.core.testSuite
import de.infix.testBalloon.framework.core.TestCompartment
import io.github.stream29.kodex.rpc.models.CliFrontendSettings
import io.github.stream29.kodex.rpc.models.NotificationHook
import io.github.stream29.kodex.rpc.models.NotificationHookType
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val unifiedSettingsStoreTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("read missing file has no side effects and writes preserve the other section") {
        withUnifiedTest { directory ->
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
        withUnifiedTest { directory ->
            val store = UnifiedSettingsStore.open(directory, backendDefaults())
            SystemCoroutineFileSystem.createDirectories(directory)
            SystemCoroutineFileSystem.writeString(store.path, "backend:\n  daemon_ttl_seconds: 9\nfrontend: {}\n")
            assertEquals(300L, store.current.backend.daemonTtlSeconds)
            store.backendStore.update { it.copy(daemonTtlSeconds = -1) }
            assertEquals(-1L, store.readCandidate().backend.daemonTtlSeconds)
        }
    }
    test("reload validation and concurrent runtime changes cannot partially publish") {
        withUnifiedTest { directory ->
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
        withUnifiedTest { directory ->
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

private suspend fun withUnifiedTest(block: suspend (Path) -> Unit) = withSplitSettingsDirectory(block)
