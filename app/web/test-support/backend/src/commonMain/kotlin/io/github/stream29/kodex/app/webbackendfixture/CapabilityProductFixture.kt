package io.github.stream29.kodex.app.webbackendfixture

import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.osenvironment.environmentVariable
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.*

/** Only the external OpenAI transport is fake; backend attempts, callbacks and persistence are real. */
internal class CapabilityProductFixture(val launchDirectory: Path, private val providerOrigin: String) {
    fun loginClient(): OpenAiLoginClient = object : OpenAiLoginClient {
        private val client = HttpClient(CIO) { followRedirects = false }

        override fun authorizationUrl(request: OpenAiLoginAuthorization): String =
            URLBuilder("$providerOrigin/openai-authorize").apply {
                parameters.append("response_type", "code")
                parameters.append("client_id", "capability-openai")
                parameters.append("redirect_uri", request.redirectUri)
                parameters.append("state", request.state)
                parameters.append("code_challenge", request.codeChallenge)
                parameters.append("code_challenge_method", "S256")
            }.buildString()

        override suspend fun exchangeAuthorizationCode(
            request: OpenAiAuthorizationCodeExchange,
        ): OpenAiLoginResult<OpenAiSubscriptionTokens> {
            val response = client.post("$providerOrigin/openai-token") {
                setBody(FormDataContent(Parameters.build {
                    append("grant_type", "authorization_code")
                    append("client_id", "capability-openai")
                    append("code", request.authorizationCode)
                    append("code_verifier", request.codeVerifier)
                    append("redirect_uri", request.redirectUri)
                }))
            }
            check(response.status == HttpStatusCode.OK) { "Task-only OpenAI exchange rejected." }
            val tokens = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            return OpenAiResult.Success(OpenAiSubscriptionTokens(
                tokens.getValue("id_token").jsonPrimitive.content,
                tokens.getValue("access_token").jsonPrimitive.content,
                tokens.getValue("refresh_token").jsonPrimitive.content,
                tokens.getValue("account_id").jsonPrimitive.content,
            ))
        }

        override suspend fun refreshSubscriptionTokens(refreshToken: String): OpenAiLoginResult<OpenAiSubscriptionTokenRefresh> =
            error("Fresh task-only credentials must not refresh.")

        override fun close() { client.close() }
    }
}

internal suspend fun prepareCapabilityProductFixture(home: Path): CapabilityProductFixture {
    val origin = requireNotNull(environmentVariable("KODEX_CAPABILITY_PROVIDER_ORIGIN"))
    val url = Url(origin)
    require(url.protocol.name == "http" && url.host == "127.0.0.1" && url.port > 0 &&
        origin == "http://127.0.0.1:${url.port}") { "Only the task's exact loopback provider is allowed." }
    val launch = Path(requireNotNull(environmentVariable("KODEX_CAPABILITY_LAUNCH_DIRECTORY")))
    val sandbox = Path(requireNotNull(environmentVariable("KODEX_CAPABILITY_SANDBOX")))
    val osHome = Path(requireNotNull(environmentVariable("HOME")))
    val temporary = Path(requireNotNull(environmentVariable("TMPDIR")))
    require(launch.isAbsolute && osHome.isAbsolute && launch != home && osHome != home &&
        sandbox.toString().matches(Regex("/(?:private/)?tmp/kodex-capabilities-[A-Za-z0-9]+")) &&
        launch == Path(sandbox, "native-launch") && osHome == Path(sandbox, "os-home") &&
        temporary == Path(sandbox, "tmp") &&
        home.parent == temporary && environmentVariable("SHELL") == "/bin/sh" &&
        listOf("ENV", "BASH_ENV", "ZDOTDIR").all { environmentVariable(it) == null } &&
        SystemFileSystem.exists(launch) && SystemFileSystem.exists(osHome)
    ) { "Use the controller's private OS Home, launch directory and clean non-login shell." }
    for (relative in listOf("Alpha", "Alpha/nested", "beta", "目录 space", "context-source")) {
        SystemCoroutineFileSystem.createDirectories(Path(launch, relative), mustCreate = true)
    }
    SystemCoroutineFileSystem.writeString(Path(launch, "not-a-directory"), "harmless fixture file")
    return CapabilityProductFixture(launch, origin)
}
