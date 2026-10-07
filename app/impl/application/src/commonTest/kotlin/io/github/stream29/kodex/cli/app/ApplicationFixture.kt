package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.app.migration.prepareKodexHome
import io.github.stream29.kodex.app.test.deleteTestDirectory
import io.github.stream29.kodex.app.test.testAnswer
import io.github.stream29.kodex.cli.settings.openBackendSettings
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.rpc.server.defaultBackendSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

internal suspend fun applicationFixture(
    response: suspend FlowCollector<ResponsesStreamEvent>.() -> Unit = { testAnswer() },
    block: suspend CoroutineScope.(KodexApplication, Path) -> Unit,
) = withContext(Dispatchers.Default) { withTimeout(45.seconds) {
    val home = Path(SystemTemporaryDirectory, "kodex-application-rpc-${Random.nextLong()}")
    try {
        val handle = prepareKodexHome(home)
        try {
            openBackendSettings(home, defaultBackendSettings()).update {
                it.copy(sessionTitle = it.sessionTitle.copy(enabled = false))
            }
            withKodexApplication(
                handle, home, Path(home, "codex"), Path(home, "agents"), applicationWidth = 120,
                createClient = { applicationClient(response) }, createLoginClient = { ApplicationLoginClient },
            ) { app -> block(app, home) }
        } finally { withContext(NonCancellable) { handle.closeAndJoin() } }
    } finally { withContext(NonCancellable) { deleteTestDirectory(home) } }
} }

internal fun applicationClient(
    response: suspend FlowCollector<ResponsesStreamEvent>.() -> Unit = { testAnswer() },
): OpenAiClient = mockOpenAiClient {
    listModels { OpenAiResult.Success(ModelsResponse(listOf(ModelInfo(
        OpenAiModelId("test-model"), "Test", contextWindow = 100_000, maxContextWindow = 100_000,
    )))) }
    createResponse { flow { response() } }
}

internal object ApplicationLoginClient : OpenAiLoginClient {
    override fun authorizationUrl(request: OpenAiLoginAuthorization): String = "https://login.example.invalid"
    override suspend fun exchangeAuthorizationCode(
        request: OpenAiAuthorizationCodeExchange,
    ): OpenAiLoginResult<OpenAiSubscriptionTokens> =
        error("No credentials are exchanged by these tests.")
    override suspend fun refreshSubscriptionTokens(
        refreshToken: String,
    ): OpenAiLoginResult<OpenAiSubscriptionTokenRefresh> =
        error("No credentials are present in these isolated Homes.")
}
