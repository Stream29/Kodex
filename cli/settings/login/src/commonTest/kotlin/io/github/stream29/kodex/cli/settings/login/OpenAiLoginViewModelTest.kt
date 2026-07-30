package io.github.stream29.kodex.cli.settings.login

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.cli.auth.KodexAuthState
import io.github.stream29.kodex.cli.auth.KodexAuthStore
import io.github.stream29.kodex.cli.auth.KodexAuthLoginAttempt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

val openAiLoginViewModelTest by testSuite {
    test("authorization URL is emitted as an effect instead of popup state") {
        coroutineScope {
            val attempt = TestLoginAttempt("https://auth.example.test/authorize?state=secret-state")
            val viewModel = OpenAiLoginViewModel(TestKodexAuthStore(attempt))
            try {
                viewModel.start()

                val effect = withTimeout(1_000) {
                    assertIs<OpenAiLoginEffect.OpenExternalUrl>(viewModel.effects.first())
                }
                assertEquals(attempt.authorizationUrl, effect.url)
                assertEquals(OpenAiLoginState.WaitingForAuthorization, viewModel.state.value)
                assertFalse(viewModel.state.value.toString().contains("secret-state"))

                attempt.complete()
                assertEquals(
                    OpenAiLoginState.Completed,
                    withTimeout(1_000) {
                        viewModel.state.first { state -> state == OpenAiLoginState.Completed }
                    },
                )
            } finally {
                viewModel.close()
            }
        }
    }

    test("retry reuses the active callback attempt") {
        coroutineScope {
            val attempt = TestLoginAttempt("https://auth.example.test/authorize?state=secret-state")
            val viewModel = OpenAiLoginViewModel(TestKodexAuthStore(attempt))
            try {
                viewModel.start()
                val first = withTimeout(1_000) {
                    assertIs<OpenAiLoginEffect.OpenExternalUrl>(viewModel.effects.first())
                }
                viewModel.onBrowserOpenFailed(first.attemptId)

                assertEquals(OpenAiLoginState.BrowserOpenFailed, viewModel.state.value)
                viewModel.retryBrowser()

                val retry = withTimeout(1_000) {
                    assertIs<OpenAiLoginEffect.OpenExternalUrl>(viewModel.effects.first())
                }
                assertEquals(first.attemptId, retry.attemptId)
                assertEquals(first.url, retry.url)
                assertEquals(OpenAiLoginState.WaitingForAuthorization, viewModel.state.value)
            } finally {
                viewModel.close()
            }
        }
    }
}

private class TestKodexAuthStore(
    private val attempt: TestLoginAttempt,
) : KodexAuthStore {
    override val state = MutableStateFlow<KodexAuthState>(
        KodexAuthState.Unavailable("Authentication is not configured."),
    )

    override suspend fun reload(): Unit = Unit

    override suspend fun startKodexLogin(): KodexAuthLoginAttempt = attempt

    override fun close(): Unit = Unit
}

private class TestLoginAttempt(
    override val authorizationUrl: String,
) : KodexAuthLoginAttempt {
    private val completion = CompletableDeferred<Unit>()

    override suspend fun awaitCompletion() {
        completion.await()
    }

    override fun cancel() {
        completion.cancel()
    }

    fun complete() {
        completion.complete(Unit)
    }
}
