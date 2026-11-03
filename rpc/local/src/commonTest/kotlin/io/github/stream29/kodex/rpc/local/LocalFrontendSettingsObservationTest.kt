@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.rpc.local

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.backend.createBackendLifecycle
import io.github.stream29.kodex.rpc.models.CliFrontendSettings
import io.github.stream29.kodex.rpc.models.NotificationHook
import io.github.stream29.kodex.rpc.models.NotificationHookType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

private fun observedSettings(name: String) = CliFrontendSettings(
    hooks = listOf(NotificationHook(name, setOf(NotificationHookType.StopUnhandledError), "fake only")),
)

internal val localFrontendSettingsObservationTest by testSuite {
    test("first live snapshot gates readiness and later frontend hooks use the same projection") {
        runTest {
            val input = MutableSharedFlow<CliFrontendSettings>()
            val closed = CompletableDeferred<Unit>()
            val ready = CompletableDeferred<StateFlow<CliFrontendSettings>>()
            val lifetime = launch {
                withFrontendSettingsObservation(input, { closed.await() }) {
                    ready.complete(it)
                    awaitCancellation()
                }
            }
            runCurrent()
            assertFalse(ready.isCompleted)
            assertEquals(1, input.subscriptionCount.value)
            input.emit(observedSettings("initial"))
            val projection = ready.await()
            assertEquals("initial", projection.value.hooks.single().name)
            input.emit(observedSettings("updated"))
            runCurrent()
            assertEquals("updated", projection.value.hooks.single().name)
            lifetime.cancelAndJoin()
            assertEquals(0, input.subscriptionCount.value)
            assertFalse(closed.isCompleted)
        }
    }
    test("fake-clock idle backend expires while the configuration observer remains subscribed") {
        runTest {
            val lifecycle = createBackendLifecycle(1.seconds, testScheduler.timeSource)
            assertTrue(lifecycle.markReady())
            val input = MutableStateFlow(observedSettings("live"))
            val ready = CompletableDeferred<Unit>()
            val lifetime = launch {
                withFrontendSettingsObservation(input, { awaitCancellation() }) {
                    ready.complete(Unit)
                    awaitCancellation()
                }
            }
            ready.await()
            advanceTimeBy(1_001)
            assertEquals(1, input.subscriptionCount.value)
            // Neither initial subscription nor elapsed observation invokes lifecycle.renew().
            assertTrue(lifecycle.expireIfIdle())
            lifetime.cancelAndJoin()
            assertEquals(0, input.subscriptionCount.value)
        }
    }
    test("transport loss cancels the execution owner and does not reconnect or resubscribe") {
        runTest {
            val input = MutableStateFlow(observedSettings("live"))
            val closed = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            var cleaned = false
            var subscriptions = 0
            val failure = async {
                runCatching {
                    withFrontendSettingsObservation(input.onStart { subscriptions++ }, { closed.await() }) {
                        entered.complete(Unit)
                        try { awaitCancellation() } finally { cleaned = true }
                    }
                }.exceptionOrNull()
            }
            entered.await()
            closed.complete(Unit)
            assertIs<BackendConnectionLost>(failure.await())
            assertTrue(cleaned)
            advanceTimeBy(60_000)
            assertEquals(1, subscriptions)
            assertEquals(0, input.subscriptionCount.value)
        }
    }
    test("normal stream end and stream failure both end ownership instead of retaining stale settings") {
        runTest {
            for (stream in listOf(
                flowOf(observedSettings("last")),
                flow<CliFrontendSettings> { emit(observedSettings("last")); error("fake stream failure") },
            )) {
                val failure = runCatching {
                    withFrontendSettingsObservation(stream, { awaitCancellation() }) { awaitCancellation() }
                }.exceptionOrNull()
                assertNotNull(failure)
            }
        }
    }
    test("cancel before first snapshot unsubscribes without publishing default empty hooks") {
        runTest {
            val input = MutableSharedFlow<CliFrontendSettings>()
            var entered = false
            val lifetime = launch {
                withFrontendSettingsObservation(input, { awaitCancellation() }) { entered = true }
            }
            runCurrent()
            lifetime.cancelAndJoin()
            assertFalse(entered)
            assertEquals(0, input.subscriptionCount.value)
        }
    }
}
