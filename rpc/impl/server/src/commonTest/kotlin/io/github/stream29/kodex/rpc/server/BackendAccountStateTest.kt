package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetOutcome
import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.test.*

val backendAccountStateTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("only the exact nonblank credit reaches the provider and refresh failure retains its outcome") {
        coroutineScope {
            val auth = AccountTestAuth()
            val requests = mutableListOf<CodexRateLimitResetConsumeRequest>()
            var failReload = false
            val client = mockOpenAiClient {
                getCodexAccountUsage {
                    if (failReload) error("Synthetic usage refresh failure.")
                    OpenAiResult.Success(CodexAccountUsageResponse())
                }
                listCodexRateLimitResetCredits {
                    OpenAiResult.Success(CodexRateLimitResetCreditsResponse(availableCount = 2))
                }
                getCodexTokenUsageProfile { OpenAiResult.Success(CodexTokenUsageProfile()) }
                consumeCodexRateLimitResetCredit { request, account ->
                    assertEquals("account-one", account.accountId)
                    requests += request
                    failReload = true
                    OpenAiResult.Success(CodexRateLimitResetConsumeResponse(CodexRateLimitResetConsumeCode.Reset))
                }
            }
            val state = BackendAccountState(this, auth, client)
            try {
                state.accountUsage.first { it is SettingsAccountUsageState.Available }
                assertFailsWith<IllegalArgumentException> { state.consumeUsageReset(" ") }
                assertTrue(requests.isEmpty())
                assertEquals(CodexRateLimitResetOutcome.Reset, state.consumeUsageReset("specific-credit"))
                assertEquals("specific-credit", requests.single().creditId)
                assertTrue(requests.single().redeemRequestId.isNotBlank())
                state.accountUsage.first { it is SettingsAccountUsageState.Failed }
            } finally { state.close() }
        }
    }
    test("all definitive outcomes remain values without retries or automatic replacement credits") {
        coroutineScope {
            val auth = AccountTestAuth()
            val seen = mutableListOf<String?>()
            var result = CodexRateLimitResetConsumeCode.NoCredit
            val client = mockOpenAiClient {
                getCodexAccountUsage { OpenAiResult.Success(CodexAccountUsageResponse()) }
                listCodexRateLimitResetCredits { OpenAiResult.Success(CodexRateLimitResetCreditsResponse(availableCount = 1)) }
                getCodexTokenUsageProfile { OpenAiResult.Success(CodexTokenUsageProfile()) }
                consumeCodexRateLimitResetCredit { request, _ ->
                    seen += request.creditId
                    OpenAiResult.Success(CodexRateLimitResetConsumeResponse(result))
                }
            }
            val state = BackendAccountState(this, auth, client)
            try {
                state.accountUsage.first { it is SettingsAccountUsageState.Available }
                for ((code, outcome) in listOf(
                    CodexRateLimitResetConsumeCode.NoCredit to CodexRateLimitResetOutcome.NoCredit,
                    CodexRateLimitResetConsumeCode.AlreadyRedeemed to CodexRateLimitResetOutcome.AlreadyRedeemed,
                    CodexRateLimitResetConsumeCode.NothingToReset to CodexRateLimitResetOutcome.NothingToReset,
                )) {
                    result = code
                    assertEquals(outcome, state.consumeUsageReset("chosen"))
                }
                assertEquals(listOf<String?>("chosen", "chosen", "chosen"), seen)
            } finally { state.close() }
        }
    }
    test("account switch clears fallback and cannot erase an already received consume result") {
        coroutineScope {
            val auth = AccountTestAuth()
            val secondFetch = CompletableDeferred<Unit>()
            val client = mockOpenAiClient {
                getCodexAccountUsage {
                    if ((auth.state.value as? OpenAiAuthState.Authenticated)?.credentials?.accountId == "account-two") {
                        secondFetch.complete(Unit)
                        awaitCancellation()
                    }
                    OpenAiResult.Success(CodexAccountUsageResponse())
                }
                listCodexRateLimitResetCredits { OpenAiResult.Success(CodexRateLimitResetCreditsResponse(availableCount = 1)) }
                getCodexTokenUsageProfile { OpenAiResult.Success(CodexTokenUsageProfile()) }
                consumeCodexRateLimitResetCredit { _, _ ->
                    auth.state.value = OpenAiAuthState.Authenticated(OpenAiSubscriptionAuthState("second", accountId = "account-two"))
                    OpenAiResult.Success(CodexRateLimitResetConsumeResponse(CodexRateLimitResetConsumeCode.Reset))
                }
            }
            val state = BackendAccountState(this, auth, client)
            try {
                state.accountUsage.first { it is SettingsAccountUsageState.Available }
                assertEquals(CodexRateLimitResetOutcome.Reset, state.consumeUsageReset("chosen"))
                secondFetch.await()
                val loading = state.accountUsage.first { it is SettingsAccountUsageState.Loading }
                assertNull(assertIs<SettingsAccountUsageState.Loading>(loading).previous)
            } finally { state.close() }
        }
    }
    test("authentication projection excludes credentials and observer cancellation leaves the shared store alive") {
        coroutineScope {
            val auth = AccountTestAuth()
            var requests = 0
            val client = mockOpenAiClient {
                getCodexAccountUsage { requests++; OpenAiResult.Success(CodexAccountUsageResponse()) }
                listCodexRateLimitResetCredits { OpenAiResult.Success(CodexRateLimitResetCreditsResponse(availableCount = 0)) }
                getCodexTokenUsageProfile { OpenAiResult.Success(CodexTokenUsageProfile()) }
            }
            val state = BackendAccountState(this, auth, client)
            try {
                val summary = assertIs<SettingsAuthenticationState.Authenticated>(state.authentication.value)
                assertEquals("account-one", summary.accountId)
                assertFalse("private-access-token" in summary.toString())
                state.accountUsage.first { it is SettingsAccountUsageState.Available }
                val observer = launch { state.accountUsage.collect() }
                observer.cancelAndJoin()
                val before = requests
                state.refreshAccountUsage()
                assertTrue(requests > before)
            } finally { state.close() }
        }
    }
}

private class AccountTestAuth : OpenAiAuthStore {
    override val state = MutableStateFlow<OpenAiAuthState>(
        OpenAiAuthState.Authenticated(OpenAiSubscriptionAuthState("private-access-token", accountId = "account-one")),
    )
}
