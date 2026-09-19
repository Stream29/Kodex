package io.github.stream29.kodex.cli.settings

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val backendSettingsCasTest by testSuite(
    compartment = { TestCompartment.RealTime },
) {
    test("mismatch on missing file neither creates a directory nor publishes the proposal") {
        withSplitSettingsDirectory { directory ->
            val initial = backendDefaults()
            val store = openBackendSettings(directory, initial)
            val different = initial.copy(authSource = KodexAuthSource.Kodex)
            assertFalse(store.compareAndSet(different, different))
            assertEquals(initial, store.settings.value)
            assertFalse(SystemCoroutineFileSystem.exists(directory))
        }
    }

    test("equal values succeed without writing even when they are different instances") {
        withSplitSettingsDirectory { directory ->
            val initial = backendDefaults()
            val store = openBackendSettings(directory, initial)
            val expect = initial.copy()
            val update = initial.copy()
            assertNotSame(initial, expect)
            assertNotSame(expect, update)
            assertTrue(store.compareAndSet(expect, update))
            assertEquals(initial, store.settings.value)
            assertFalse(SystemCoroutineFileSystem.exists(directory))
        }
    }

    test("same value on a sparse file does not canonicalize or drop unknown fields") {
        withSplitSettingsDirectory { directory ->
            val fileSystem = FaultingSettingsFileSystem()
            var writes = 0
            fileSystem.afterWrite = { writes++ }
            val initial = backendDefaults()
            val store = openBackendSettings(directory, initial, fileSystem)
            val text = "auth_source: codex\nfuture_option: preserve\n"
            writeFixture(store.settingsPath, text)
            assertTrue(store.compareAndSet(initial.copy(), initial.copy()))
            assertEquals(0, writes)
            assertEquals(text, SystemCoroutineFileSystem.readString(store.settingsPath))
        }
    }

    test("a stale compare observes the actual file but never persists the rejected value") {
        withSplitSettingsDirectory { directory ->
            val initial = backendDefaults()
            val first = openBackendSettings(directory, initial)
            val second = openBackendSettings(directory, initial)
            val actual = second.update { it.copy(authSource = KodexAuthSource.Kodex) }
            val text = SystemCoroutineFileSystem.readString(first.settingsPath)
            val rejected = initial.copy(sessionTitle = initial.sessionTitle.copy(enabled = false))
            assertFalse(first.compareAndSet(initial, rejected))
            assertEquals(actual, first.settings.value)
            assertEquals(text, SystemCoroutineFileSystem.readString(first.settingsPath))
        }
    }

    test("matching file CAS persists and publishes the whole proposed value") {
        withSplitSettingsDirectory { directory ->
            val initial = backendDefaults()
            val store = openBackendSettings(directory, initial)
            val update = initial.copy(
                authSource = KodexAuthSource.Kodex,
                sessionTitle = initial.sessionTitle.copy(enabled = false),
                newSession = initial.newSession.copy(model = OpenAiModelId("selected")),
            )
            assertTrue(store.compareAndSet(initial.copy(), update))
            assertEquals(update, store.settings.value)
            assertEquals(update, openBackendSettings(directory, initial).settings.value)
        }
    }

    test("only one concurrent CAS with the same expectation succeeds") {
        withSplitSettingsDirectory { directory ->
            val initial = backendDefaults()
            val fileSystem = FaultingSettingsFileSystem()
            var writes = 0
            fileSystem.afterWrite = { writes++ }
            val store = openBackendSettings(directory, initial, fileSystem)
            val proposals = (0 until 8).map { index ->
                initial.copy(newSession = initial.newSession.copy(model = OpenAiModelId("model-$index")))
            }
            val results = coroutineScope {
                proposals.map { value ->
                    async(Dispatchers.Default) { store.compareAndSet(initial, value) }
                }.awaitAll()
            }
            assertEquals(1, results.count { it })
            assertEquals(1, writes)
            val winner = proposals[results.indexOf(true)]
            assertEquals(winner, store.settings.value)
            assertEquals(winner, openBackendSettings(directory, initial).settings.value)
        }
    }

    test("update waits for CAS and preserves its committed fields") {
        withSplitSettingsDirectory { directory ->
            val initial = backendDefaults()
            val fileSystem = FaultingSettingsFileSystem()
            val store = openBackendSettings(directory, initial, fileSystem)
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            fileSystem.beforeMove = {
                if (entered.complete(Unit)) finish.await()
            }
            coroutineScope {
                val comparison = async {
                    store.compareAndSet(initial, initial.copy(authSource = KodexAuthSource.Kodex))
                }
                try {
                    withTimeout(10.seconds) { entered.await() }
                    var transformed = false
                    val update = async(start = CoroutineStart.UNDISPATCHED) {
                        store.update {
                            transformed = true
                            it.copy(sessionTitle = it.sessionTitle.copy(enabled = false))
                        }
                    }
                    assertFalse(transformed)
                    finish.complete(Unit)
                    assertTrue(comparison.await())
                    val result = update.await()
                    assertEquals(KodexAuthSource.Kodex, result.authSource)
                    assertFalse(result.sessionTitle.enabled)
                } finally {
                    finish.complete(Unit)
                }
            }
            assertEquals(store.settings.value, openBackendSettings(directory, initial).settings.value)
        }
    }

    test("CAS waits for update and rejects an expectation made stale by that update") {
        withSplitSettingsDirectory { directory ->
            val initial = backendDefaults()
            val fileSystem = FaultingSettingsFileSystem()
            val store = openBackendSettings(directory, initial, fileSystem)
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            fileSystem.beforeMove = {
                if (entered.complete(Unit)) finish.await()
            }
            coroutineScope {
                val update = async { store.update { it.copy(authSource = KodexAuthSource.Kodex) } }
                try {
                    withTimeout(10.seconds) { entered.await() }
                    val comparison = async(start = CoroutineStart.UNDISPATCHED) {
                        store.compareAndSet(
                            initial,
                            initial.copy(sessionTitle = initial.sessionTitle.copy(enabled = false)),
                        )
                    }
                    assertFalse(comparison.isCompleted)
                    finish.complete(Unit)
                    val committed = update.await()
                    assertFalse(comparison.await())
                    assertEquals(committed, store.settings.value)
                } finally {
                    finish.complete(Unit)
                }
            }
        }
    }

    test("reload waits for a pending CAS instead of overwriting it with the old file") {
        withSplitSettingsDirectory { directory ->
            val initial = backendDefaults()
            val fileSystem = FaultingSettingsFileSystem()
            val store = openBackendSettings(directory, initial, fileSystem)
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            fileSystem.beforeMove = {
                entered.complete(Unit)
                finish.await()
            }
            val updated = initial.copy(authSource = KodexAuthSource.Kodex)
            coroutineScope {
                val comparison = async { store.compareAndSet(initial, updated) }
                try {
                    withTimeout(10.seconds) { entered.await() }
                    val reloaded = async(start = CoroutineStart.UNDISPATCHED) { store.reload() }
                    assertFalse(reloaded.isCompleted)
                    finish.complete(Unit)
                    assertTrue(comparison.await())
                    assertEquals(updated, reloaded.await())
                } finally {
                    finish.complete(Unit)
                }
            }
        }
    }

    test("invalid file is a failure not a comparison mismatch") {
        withSplitSettingsDirectory { directory ->
            val initial = backendDefaults()
            val store = openBackendSettings(directory, initial)
            writeFixture(store.settingsPath, "auth_source: invalid\n")
            assertFailsWith<IllegalArgumentException> {
                store.compareAndSet(initial, initial.copy(authSource = KodexAuthSource.Kodex))
            }
            assertEquals(initial, store.settings.value)
            assertEquals("auth_source: invalid\n", SystemCoroutineFileSystem.readString(store.settingsPath))
        }
    }

    test("persistence failure propagates without publishing the failed proposal") {
        withSplitSettingsDirectory { directory ->
            val initial = backendDefaults()
            val fileSystem = FaultingSettingsFileSystem()
            val store = openBackendSettings(directory, initial, fileSystem)
            val failure = IOException("replacement failed")
            fileSystem.beforeMove = { throw failure }
            assertSame(failure, assertFailsWith<IOException> {
                store.compareAndSet(initial, initial.copy(authSource = KodexAuthSource.Kodex))
            })
            assertEquals(initial, store.settings.value)
            assertFalse(SystemCoroutineFileSystem.exists(store.settingsPath))
            assertEquals(emptyList(), SystemCoroutineFileSystem.list(directory).toList())
        }
    }

    test("cancelling CAS before replacement cleans up without writing or publishing") {
        withSplitSettingsDirectory { directory ->
            val initial = backendDefaults()
            val fileSystem = FaultingSettingsFileSystem()
            val store = openBackendSettings(directory, initial, fileSystem)
            val entered = CompletableDeferred<Unit>()
            fileSystem.beforeMove = {
                entered.complete(Unit)
                awaitCancellation()
            }
            coroutineScope {
                val comparison = launch {
                    store.compareAndSet(initial, initial.copy(authSource = KodexAuthSource.Kodex))
                }
                try {
                    withTimeout(10.seconds) { entered.await() }
                } finally {
                    comparison.cancelAndJoin()
                }
                assertTrue(comparison.isCancelled)
            }
            assertEquals(initial, store.settings.value)
            assertFalse(SystemCoroutineFileSystem.exists(store.settingsPath))
            assertEquals(emptyList(), SystemCoroutineFileSystem.list(directory).toList())
        }
    }
}
