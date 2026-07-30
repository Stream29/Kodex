package io.github.stream29.kodex.cli.auth

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.OpenAiAuthorizationCodeExchange
import io.github.stream29.kodex.openai.OpenAiLoginAuthorization
import io.github.stream29.kodex.openai.OpenAiSubscriptionTokenRefresh
import io.github.stream29.kodex.openai.OpenAiSubscriptionTokens
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout

val localKodexLoginAttemptJvmTest by testSuite(
    compartment = { TestCompartment.RealTime },
) {
    test("loopback callback exchanges the verified authorization code") {
        val expectedTokens = OpenAiSubscriptionTokens(
            idToken = "id-token",
            accessToken = "access-token",
            refreshToken = "refresh-token",
            accountId = "account-id",
        )
        val client = RecordingLoginClient(expectedTokens)
        val scope = CoroutineScope(currentCoroutineContext())
        var persisted: OpenAiSubscriptionTokens? = null
        var finished = false
        val attempt = LocalKodexLoginAttempt.start(
            scope = scope,
            loginClient = client,
            persistTokens = { tokens -> persisted = tokens },
            onFinished = { finished = true },
            callbackPorts = listOf(0),
        )
        try {
            val authorization = requireNotNull(client.authorization)
            val callback = URI(
                "${authorization.redirectUri}?code=callback-code&state=${authorization.state}",
            ).toURL().openConnection() as HttpURLConnection
            try {
                assertEquals(200, callback.responseCode)
                callback.inputStream.close()
            } finally {
                callback.disconnect()
            }

            withTimeout(5.seconds) { attempt.awaitCompletion() }

            assertEquals(expectedTokens, persisted)
            val exchange = requireNotNull(client.exchange)
            assertEquals("callback-code", exchange.authorizationCode)
            assertEquals(authorization.redirectUri, exchange.redirectUri)
            assertEquals(authorization.codeChallenge, pkceCodeChallenge(exchange.codeVerifier))
            assertEquals(true, finished)
        } finally {
            attempt.cancel()
        }
    }

    test("cancelling a callback attempt still releases its owner") {
        val client = RecordingLoginClient(
            OpenAiSubscriptionTokens(
                idToken = "id-token",
                accessToken = "access-token",
                refreshToken = "refresh-token",
            ),
        )
        val scope = CoroutineScope(currentCoroutineContext())
        var finished = false
        val attempt = LocalKodexLoginAttempt.start(
            scope = scope,
            loginClient = client,
            persistTokens = {},
            onFinished = { finished = true },
            callbackPorts = listOf(0),
        )
        val completion = scope.async { attempt.awaitCompletion() }
        attempt.cancel()

        val failure = withTimeout(5.seconds) {
            try {
                completion.await()
                error("Expected cancelling the callback attempt to fail its completion.")
            } catch (failure: Throwable) {
                failure
            }
        }
        assertIs<kotlinx.coroutines.CancellationException>(failure)
        assertTrue(finished)
    }
}

private class RecordingLoginClient(
    private val tokens: OpenAiSubscriptionTokens,
) : OpenAiLoginClient {
    var authorization: OpenAiLoginAuthorization? = null
    var exchange: OpenAiAuthorizationCodeExchange? = null

    override fun authorizationUrl(request: OpenAiLoginAuthorization): String {
        authorization = request
        return "https://auth.example.test/authorize"
    }

    override suspend fun exchangeAuthorizationCode(
        request: OpenAiAuthorizationCodeExchange,
    ): OpenAiSubscriptionTokens {
        exchange = request
        return tokens
    }

    override suspend fun refreshSubscriptionTokens(refreshToken: String): OpenAiSubscriptionTokenRefresh =
        error("Token refresh is not used by this test.")
}
