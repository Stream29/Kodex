package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetOutcome
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.inmemory.withInMemoryRpc
import io.github.stream29.kodex.rpc.models.OAuthTarget
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.rpc.withService
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

val backendServicesOAuthSocketTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("real RPC and backend loopback persist credentials and retain usage behavior") {
        withServices { services, login ->
            withInMemoryRpc(services::register) { raw ->
                val global = RestoringRpcClient(raw).withService<GlobalRpc>()
                assertIs<SettingsAuthenticationState.Unavailable>(global.getAuthentication())
                val old = global.getSettings()
                assertTrue(global.compareAndSetSettings(old, old.copy(authSource = KodexAuthSource.Kodex)))
                val authorization = global.startOAuthLogin(OAuthTarget.OpenAi(KodexAuthSource.Kodex))
                val url = Url(authorization.url)
                val redirect = requireNotNull(url.parameters["redirect_uri"])
                val callback = URLBuilder(redirect).apply {
                    parameters.append("state", requireNotNull(url.parameters["state"]))
                    parameters.append("code", "one-code")
                }.buildString()
                HttpClient(CIO).use { client -> assertEquals(HttpStatusCode.OK, client.get(callback).status) }
                global.awaitOAuthLogin(authorization.attemptId)
                global.awaitOAuthLogin(authorization.attemptId)
                val summary = global.getAuthenticationFlow().filterIsInstance<SettingsAuthenticationState.Authenticated>().first()
                assertEquals("account", summary.accountId)
                assertEquals(1, login.exchanges)
                global.getModelsFlow().first { models -> models.any { it.slug == OpenAiModelId("test-model") } }
                global.getAccountUsageFlow().filterIsInstance<SettingsAccountUsageState.Available>().first()
                global.refreshAccountUsage()
                assertEquals(CodexRateLimitResetOutcome.NoCredit, global.consumeUsageReset("specific"))
                global.cancelOAuthLogin(authorization.attemptId)
                global.removeAuthentication(KodexAuthSource.Kodex)
                global.getAuthenticationFlow().filterIsInstance<SettingsAuthenticationState.Unavailable>().first()
            }
        }
    }
}
