package io.github.stream29.kodex.cli.settings

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.rpc.models.BackendSettings
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CancellationException
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
import kotlinx.io.files.Path
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
        withSettingsDirectory { directory ->
            val initial = backendDefaults()
            val store = UnifiedSettingsStore.open(directory, initial).backendStore
            val different = initial.copy(authSource = KodexAuthSource.Kodex)
            assertFalse(store.compareAndSet(different, different))
            assertEquals(initial, store.settings.value)
            assertFalse(SystemCoroutineFileSystem.exists(directory))
        }
    }

    test("equal values succeed without writing even when they are different instances") {
        withSettingsDirectory { directory ->
            val initial = backendDefaults()
            val store = UnifiedSettingsStore.open(directory, initial).backendStore
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
        withSettingsDirectory { directory ->
            val fileSystem = FaultingSettingsFileSystem()
            var writes = 0
            fileSystem.afterWrite = { writes++ }
            val initial = backendDefaults()
            val path = Path(directory, "settings.yml")
            val text = "backend:\n  auth_source: codex\n  future_option: preserve\n"
            writeFixture(path, text)
            val store = UnifiedSettingsStore.open(directory, initial, fileSystem).backendStore
            assertTrue(store.compareAndSet(initial.copy(), initial.copy()))
            assertEquals(0, writes)
            assertEquals(text, SystemCoroutineFileSystem.readString(path))
        }
    }

    test("a stale compare never persists the rejected value") {
        withSettingsDirectory { directory ->
            val initial = backendDefaults()
            val unified = UnifiedSettingsStore.open(directory, initial)
            val store = unified.backendStore
            val actual = store.update { it.copy(authSource = KodexAuthSource.Kodex) }
            val text = SystemCoroutineFileSystem.readString(unified.path)
            val rejected = initial.copy(sessionTitle = initial.sessionTitle.copy(enabled = false))
            assertFalse(store.compareAndSet(initial, rejected))
            assertEquals(actual, store.settings.value)
            assertEquals(text, SystemCoroutineFileSystem.readString(unified.path))
        }
    }

    test("matching file CAS persists and publishes the whole proposed value") {
        withSettingsDirectory { directory ->
            val initial = backendDefaults()
            val store = UnifiedSettingsStore.open(directory, initial).backendStore
            val update = initial.copy(
                authSource = KodexAuthSource.Kodex,
                sessionTitle = initial.sessionTitle.copy(enabled = false),
                newSession = initial.newSession.copy(model = OpenAiModelId("selected")),
            )
            assertTrue(store.compareAndSet(initial.copy(), update))
            assertEquals(update, store.settings.value)
            assertEquals(update, UnifiedSettingsStore.open(directory, initial).backendSettings.value)
        }
    }

    test("stale and equal CAS skip validation while a real change validates the exact locked values once") {
        withSettingsDirectory { directory ->
            val initial = backendDefaults()
            val fileSystem = FaultingSettingsFileSystem()
            var writes = 0
            fileSystem.afterWrite = { writes++ }
            val store = UnifiedSettingsStore.open(directory, initial, fileSystem).backendStore
            val proposal = initial.copy(authSource = KodexAuthSource.Kodex)
            var validations = 0
            val validate: (BackendSettings, BackendSettings) -> Unit =
                { current, update ->
                    validations++
                    assertEquals(initial, current)
                    assertEquals(proposal, update)
                    assertEquals(current, store.settings.value)
                    assertEquals(0, writes)
                }
            assertFalse(store.compareAndSet(proposal, proposal, validate))
            assertTrue(store.compareAndSet(initial, initial.copy(), validate))
            assertEquals(0, validations)
            assertFalse(SystemCoroutineFileSystem.exists(directory))
            assertTrue(store.compareAndSet(initial, proposal, validate))
            assertEquals(1, validations)
            assertEquals(proposal, store.settings.value)
        }
    }

    test("validation failure preserves the actual snapshot and original throwable without rewriting") {
        withSettingsDirectory { directory ->
            val initial = backendDefaults()
            val unified = UnifiedSettingsStore.open(directory, initial)
            val store = unified.backendStore
            val actual = initial.copy(authSource = KodexAuthSource.Kodex)
            store.update { actual }
            val original = SystemCoroutineFileSystem.readString(unified.path)
            val failure = IllegalStateException("validation rejected")
            assertSame(failure, assertFailsWith<IllegalStateException> {
                store.compareAndSet(actual, initial) { current, update ->
                    assertEquals(actual, current)
                    assertEquals(initial, update)
                    assertEquals(actual, store.settings.value)
                    throw failure
                }
            })
            assertEquals(actual, store.settings.value)
            assertEquals(original, SystemCoroutineFileSystem.readString(unified.path))
        }
    }

    test("lost CAS completion after replacement leaves persisted proposal without claiming rollback") {
        withSettingsDirectory { directory ->
            val initial = backendDefaults()
            val fileSystem = FaultingSettingsFileSystem()
            val unified = UnifiedSettingsStore.open(directory, initial, fileSystem)
            val store = unified.backendStore
            val proposal = initial.copy(authSource = KodexAuthSource.Kodex)
            val failure = CancellationException("CAS completion lost")
            fileSystem.afterMove = { throw failure }
            assertSame(failure, assertFailsWith<CancellationException> {
                store.compareAndSet(initial, proposal)
            })
            assertEquals(initial, store.settings.value)
            assertEquals(proposal, UnifiedSettingsStore.open(directory, initial).backendSettings.value)
            assertEquals(listOf(unified.path), SystemCoroutineFileSystem.list(directory).toList())
        }
    }

    test("only one concurrent CAS with the same expectation succeeds") {
        withSettingsDirectory { directory ->
            val initial = backendDefaults()
            val fileSystem = FaultingSettingsFileSystem()
            var writes = 0
            fileSystem.afterWrite = { writes++ }
            val store = UnifiedSettingsStore.open(directory, initial, fileSystem).backendStore
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
            assertEquals(winner, UnifiedSettingsStore.open(directory, initial).backendSettings.value)
        }
    }

    test("update waits for CAS and preserves its committed fields") {
        withSettingsDirectory { directory ->
            val initial = backendDefaults()
            val fileSystem = FaultingSettingsFileSystem()
            val store = UnifiedSettingsStore.open(directory, initial, fileSystem).backendStore
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
            assertEquals(store.settings.value, UnifiedSettingsStore.open(directory, initial).backendSettings.value)
        }
    }

    test("CAS waits for update and rejects an expectation made stale by that update") {
        withSettingsDirectory { directory ->
            val initial = backendDefaults()
            val fileSystem = FaultingSettingsFileSystem()
            val store = UnifiedSettingsStore.open(directory, initial, fileSystem).backendStore
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

    test("acceptReload waits for a pending CAS instead of overwriting it with the old file") {
        withSettingsDirectory { directory ->
            val initial = backendDefaults()
            val fileSystem = FaultingSettingsFileSystem()
            val unified = UnifiedSettingsStore.open(directory, initial, fileSystem)
            val store = unified.backendStore
            val expected = unified.current
            val candidate = unified.readCandidate()
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
                    val reloaded = async(start = CoroutineStart.UNDISPATCHED) {
                        unified.acceptReload(expected, candidate) { _, _ -> }
                    }
                    assertFalse(reloaded.isCompleted)
                    finish.complete(Unit)
                    assertTrue(comparison.await())
                    assertFalse(reloaded.await())
                    assertEquals(updated, store.settings.value)
                } finally {
                    finish.complete(Unit)
                }
            }
        }
    }

    test("invalid file fails readCandidate without mutating runtime state") {
        withSettingsDirectory { directory ->
            val initial = backendDefaults()
            val unified = UnifiedSettingsStore.open(directory, initial)
            writeFixture(unified.path, "backend:\n  auth_source: invalid\n")
            assertFailsWith<IllegalArgumentException> {
                unified.readCandidate()
            }
            assertEquals(initial, unified.backendSettings.value)
            assertEquals("backend:\n  auth_source: invalid\n", SystemCoroutineFileSystem.readString(unified.path))
        }
    }

    test("persistence failure propagates without publishing the failed proposal") {
        withSettingsDirectory { directory ->
            val initial = backendDefaults()
            val fileSystem = FaultingSettingsFileSystem()
            val unified = UnifiedSettingsStore.open(directory, initial, fileSystem)
            val store = unified.backendStore
            val failure = IOException("replacement failed")
            fileSystem.beforeMove = { throw failure }
            assertSame(failure, assertFailsWith<IOException> {
                store.compareAndSet(initial, initial.copy(authSource = KodexAuthSource.Kodex))
            })
            assertEquals(initial, store.settings.value)
            assertFalse(SystemCoroutineFileSystem.exists(unified.path))
            assertEquals(emptyList(), SystemCoroutineFileSystem.list(directory).toList())
        }
    }

    test("cancelling CAS before replacement cleans up without writing or publishing") {
        withSettingsDirectory { directory ->
            val initial = backendDefaults()
            val fileSystem = FaultingSettingsFileSystem()
            val unified = UnifiedSettingsStore.open(directory, initial, fileSystem)
            val store = unified.backendStore
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
            assertFalse(SystemCoroutineFileSystem.exists(unified.path))
            assertEquals(emptyList(), SystemCoroutineFileSystem.list(directory).toList())
        }
    }
}
