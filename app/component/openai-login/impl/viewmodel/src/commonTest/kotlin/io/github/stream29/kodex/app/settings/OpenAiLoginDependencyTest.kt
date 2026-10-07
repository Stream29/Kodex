@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.app.settings

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.cli.auth.KodexAuthLoginAttempt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

val openAiLoginDependencyTest by testSuite {
    test("typed dependencies are lazy and duplicate start does not prepare twice") {
        runTest {
            var starts = 0
            val attempt = ControlledLoginAttempt()
            val vm = createOpenAiLoginViewModel(
                dependencies = OpenAiLoginDependencies { starts++; attempt },
                ownerScope = this,
            )
            try {
                assertEquals(OpenAiLoginState.Ready, vm.state.value)
                assertEquals(0, starts)
                vm.start()
                vm.start()
                assertEquals(OpenAiLoginState.Preparing, vm.state.value)
                runCurrent()
                val effect = assertIs<OpenAiLoginEffect.OpenExternalUrl>(vm.effects.first())
                vm.start()
                runCurrent()
                assertEquals(1, starts)
                assertTrue(vm.isActive(effect.attemptId))
                vm.onBrowserOpened(effect.attemptId)
                assertEquals(OpenAiLoginState.WaitingForAuthorization(effect.attemptId), vm.state.value)
                attempt.completion.complete(Unit)
                runCurrent()
                assertEquals(OpenAiLoginState.Completed, vm.state.value)
                assertFalse(vm.isActive(effect.attemptId))
                vm.onBrowserOpenFailed(effect.attemptId)
                vm.cancel()
                assertEquals(OpenAiLoginState.Completed, vm.state.value)
            } finally {
                vm.close()
            }
        }
    }

    test("stale host reports and retry cannot affect the active attempt") {
        runTest {
            var starts = 0
            val attempt = ControlledLoginAttempt()
            val vm = createOpenAiLoginViewModel(OpenAiLoginDependencies { starts++; attempt }, this)
            try {
                vm.start()
                runCurrent()
                val effect = assertIs<OpenAiLoginEffect.OpenExternalUrl>(vm.effects.first())
                vm.onBrowserOpenFailed(effect.attemptId + 1)
                assertEquals(OpenAiLoginState.WaitingForAuthorization(effect.attemptId), vm.state.value)
                vm.onBrowserOpenFailed(effect.attemptId)
                vm.onBrowserOpened(effect.attemptId + 1)
                vm.retryBrowser(effect.attemptId + 1)
                assertEquals(OpenAiLoginState.BrowserOpenFailed(effect.attemptId), vm.state.value)
                vm.retryBrowser(effect.attemptId)
                runCurrent()
                assertEquals(effect, vm.effects.first())
                assertEquals(1, starts)
                assertEquals(OpenAiLoginState.WaitingForAuthorization(effect.attemptId), vm.state.value)
            } finally {
                vm.close()
            }
        }
    }

    test("cancel invalidates buffered output and restart gets a fresh identity") {
        runTest {
            val first = ControlledLoginAttempt()
            val second = ControlledLoginAttempt()
            var starts = 0
            val vm = createOpenAiLoginViewModel(
                OpenAiLoginDependencies { if (starts++ == 0) first else second },
                this,
            )
            try {
                vm.start()
                runCurrent()
                vm.cancel()
                assertEquals(OpenAiLoginState.Ready, vm.state.value)
                vm.start()
                runCurrent()
                val oldOutput = assertIs<OpenAiLoginEffect.OpenExternalUrl>(vm.effects.first())
                val newOutput = assertIs<OpenAiLoginEffect.OpenExternalUrl>(vm.effects.first())
                assertFalse(vm.isActive(oldOutput.attemptId))
                assertTrue(vm.isActive(newOutput.attemptId))
                assertTrue(newOutput.attemptId > oldOutput.attemptId)
                vm.onBrowserOpenFailed(oldOutput.attemptId)
                assertEquals(OpenAiLoginState.WaitingForAuthorization(newOutput.attemptId), vm.state.value)
                assertEquals(1, first.cancels)
            } finally {
                vm.close()
            }
        }
    }

    test("preparation failure is display state and retry uses a new attempt") {
        runTest {
            var starts = 0
            val attempt = ControlledLoginAttempt()
            val vm = createOpenAiLoginViewModel(
                OpenAiLoginDependencies {
                    if (starts++ == 0) error(" ")
                    attempt
                },
                this,
            )
            try {
                vm.start()
                runCurrent()
                assertEquals(OpenAiLoginState.Failed("OpenAI sign-in failed."), vm.state.value)
                vm.start()
                runCurrent()
                assertEquals(2, starts)
                assertEquals(2L, assertIs<OpenAiLoginEffect.OpenExternalUrl>(vm.effects.first()).attemptId)
            } finally {
                vm.close()
            }
        }
    }

    test("authorization failure is not confused with browser launch failure") {
        runTest {
            val attempt = ControlledLoginAttempt()
            val vm = createOpenAiLoginViewModel(OpenAiLoginDependencies { attempt }, this)
            try {
                vm.start()
                runCurrent()
                val effect = assertIs<OpenAiLoginEffect.OpenExternalUrl>(vm.effects.first())
                vm.onBrowserOpenFailed(effect.attemptId)
                attempt.completion.completeExceptionally(IllegalStateException("Authorization denied"))
                runCurrent()
                assertEquals(OpenAiLoginState.Failed("Authorization denied"), vm.state.value)
                vm.retryBrowser(effect.attemptId)
                assertFalse(vm.isActive(effect.attemptId))
                assertEquals(OpenAiLoginState.Failed("Authorization denied"), vm.state.value)
            } finally {
                vm.close()
            }
        }
    }

    test("a preparation handle arriving after close is cancelled and never emitted") {
        runTest {
            val delivered = CompletableDeferred<KodexAuthLoginAttempt>()
            val attempt = ControlledLoginAttempt()
            val vm = createOpenAiLoginViewModel(
                OpenAiLoginDependencies { withContext(NonCancellable) { delivered.await() } },
                this,
            )
            vm.start()
            runCurrent()
            vm.close()
            vm.close()
            delivered.complete(attempt)
            runCurrent()
            assertEquals(1, attempt.cancels)
            assertEquals(emptyList(), vm.effects.toList())
            assertTrue(coroutineContext[Job]!!.isActive)
            val before = vm.state.value
            vm.start()
            vm.retryBrowser(1)
            vm.onBrowserOpened(1)
            vm.onBrowserOpenFailed(1)
            vm.cancel()
            runCurrent()
            assertEquals(before, vm.state.value)
            assertFalse(vm.isActive(1))
        }
    }

    test("late completion of a replaced attempt cannot complete the new child request") {
        runTest {
            val oldCompletion = CompletableDeferred<Unit>()
            var cancelled = 0
            val oldAttempt = object : KodexAuthLoginAttempt {
                override val authorizationUrl = "https://login.example.invalid/old"
                override suspend fun awaitCompletion(): Unit =
                    withContext(NonCancellable) { oldCompletion.await() }
                override fun cancel() { cancelled++ }
            }
            val next = ControlledLoginAttempt()
            var starts = 0
            val vm = createOpenAiLoginViewModel(
                OpenAiLoginDependencies { if (starts++ == 0) oldAttempt else next },
                this,
            )
            try {
                vm.start()
                runCurrent()
                val old = assertIs<OpenAiLoginEffect.OpenExternalUrl>(vm.effects.first())
                vm.cancel()
                vm.start()
                runCurrent()
                val current = assertIs<OpenAiLoginEffect.OpenExternalUrl>(vm.effects.first())
                oldCompletion.complete(Unit)
                runCurrent()
                assertEquals(1, cancelled)
                assertFalse(vm.isActive(old.attemptId))
                assertEquals(OpenAiLoginState.WaitingForAuthorization(current.attemptId), vm.state.value)
            } finally {
                oldCompletion.complete(Unit)
                vm.close()
            }
        }
    }
}

private class ControlledLoginAttempt : KodexAuthLoginAttempt {
    override val authorizationUrl = "https://login.example.invalid/authorize?state=transient-secret"
    val completion = CompletableDeferred<Unit>()
    var cancels = 0

    override suspend fun awaitCompletion(): Unit = completion.await()
    override fun cancel() {
        cancels++
        completion.cancel()
    }
}
