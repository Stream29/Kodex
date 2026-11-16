package io.github.stream29.kodex.openai.modelcatalog

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.OpenAiAuthState
import io.github.stream29.kodex.openai.OpenAiSubscriptionAuthState
import io.github.stream29.kodex.openai.client.OpenAiClient
import io.github.stream29.kodex.openai.client.OpenAiClientConfig
import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import io.github.stream29.kodex.openai.codexclistorage.CodexAuthJson
import io.github.stream29.kodex.openai.codexclistorage.CodexCliStorage
import io.github.stream29.kodex.utils.osenvironment.environmentVariable
import io.github.stream29.kodex.utils.osenvironment.userHomeDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private fun testCodexDirectory(): Path =
    environmentVariable("CODEX_HOME")
        ?.takeIf(String::isNotBlank)
        ?.let(::Path)
        ?: userHomeDirectory()?.let { home -> Path(home, ".codex") }
        ?: error("CODEX_HOME or a readable user home directory must be set for model-catalog tests.")

private suspend fun liveCatalog(): LiveCatalogFixture {
    val storage = CodexCliStorage(testCodexDirectory())
    val credentials = storage.readAuthOrNull().toSubscriptionAuthStateOrThrow()
    val authState = MutableStateFlow<OpenAiAuthState>(OpenAiAuthState.Unavailable.NotLoaded)
    val authStore = object : OpenAiAuthStore {
        override val state = authState
    }
    val client = OpenAiClient(
        authStore = authStore,
        config = OpenAiClientConfig(),
    )
    val catalog = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        .ownedOpenAiModelCatalog(client, authStore)
    return LiveCatalogFixture(client, catalog, authState, credentials)
}

private fun CodexAuthJson?.toSubscriptionAuthStateOrThrow(): OpenAiSubscriptionAuthState {
    val tokens = this?.tokens ?: error("Codex CLI auth tokens are required.")
    return OpenAiSubscriptionAuthState(
        accessToken = tokens.accessToken,
        accountId = tokens.accountId?.takeIf(String::isNotBlank),
    )
}

private class LiveCatalogFixture(
    private val client: OpenAiClient,
    val catalog: OpenAiModelCatalogStore,
    val authState: MutableStateFlow<OpenAiAuthState>,
    val credentials: OpenAiSubscriptionAuthState,
) : AutoCloseable {
    override fun close() {
        catalog.close()
        client.close()
    }
}

val openAiModelCatalogIntegrationTest by testSuite(testConfig = TestConfig.testScope(isEnabled = false)) {
    testFixture { liveCatalog() } closeWith { close() } asParameterForEach {
        test(
            "refreshes model metadata from the real Codex endpoint",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 120.seconds),
        ) { fixture ->
            val bundled = fixture.catalog.models.value
            val models = withContext(Dispatchers.Default) {
                fixture.authState.value = OpenAiAuthState.Authenticated(fixture.credentials)
                fixture.catalog.models.first { it !== bundled }
            }

            assertTrue(models.isNotEmpty())
            assertEquals(models, fixture.catalog.models.value)
        }
    }
}
