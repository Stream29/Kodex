package io.github.stream29.codex.lite.cli.auth

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.cli.settings.CodexAuthSource
import io.github.stream29.codex.lite.cli.settings.CodexGlobalSettings
import io.github.stream29.codex.lite.cli.settings.InMemoryCodexGlobalSettings
import io.github.stream29.codex.lite.openai.OpenAiSubscriptionPlan
import io.github.stream29.codex.lite.openai.OpenAiSubscriptionTokenRefresh
import io.github.stream29.codex.lite.openai.OpenAiSubscriptionTokens
import io.github.stream29.codex.lite.openai.client.contract.OpenAiLoginClient
import io.github.stream29.codex.lite.openai.codexclistorage.CodexAuthJson
import io.github.stream29.codex.lite.openai.codexclistorage.CodexAuthMode
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

val fileSystemCodexAuthStoreTest by testSuite(
    compartment = { TestCompartment.RealTime },
) {
    test("Codex source reads the shared Codex auth file without creating auth yaml") {
        withAuthDirectories("default-codex") { codexHome, dataDirectory ->
            val source = subscriptionAuth("initial")
            writeCodexAuth(codexHome, source)
            val settings = InMemoryCodexGlobalSettings(CodexGlobalSettings(codexHome = codexHome))

            coroutineScope {
                val store = FileSystemCodexAuthStore(dataDirectory, settings)
                try {
                    val auth = store.authenticated()
                    assertEquals(source.tokens?.accessToken, auth.accessToken)
                    assertEquals("account-initial", auth.accountId)
                    assertEquals(OpenAiSubscriptionPlan.Plus, auth.planType)
                    assertFalse(SystemCoroutineFileSystem.exists(Path(dataDirectory, "auth.yml")))
                } finally {
                    store.close()
                }
            }
        }
    }

    test("reload follows changes to the shared Codex auth file") {
        withAuthDirectories("reload-codex") { codexHome, dataDirectory ->
            writeCodexAuth(codexHome, subscriptionAuth("first"))
            val settings = InMemoryCodexGlobalSettings(CodexGlobalSettings(codexHome = codexHome))

            coroutineScope {
                val store = FileSystemCodexAuthStore(dataDirectory, settings)
                try {
                    val updated = subscriptionAuth("second")
                    writeCodexAuth(codexHome, updated)

                    store.reload()

                    assertEquals(updated.tokens?.accessToken, store.authenticated().accessToken)
                    assertEquals("account-second", store.authenticated().accountId)
                } finally {
                    store.close()
                }
            }
        }
    }

    test("Codex Lite source owns credentials in auth yaml") {
        withAuthDirectories("codex-lite") { codexHome, dataDirectory ->
            val local = subscriptionAuth("local")
            writeAuthFile(dataDirectory, local.toCodexLiteAuthFile())
            val settings = InMemoryCodexGlobalSettings(
                CodexGlobalSettings(
                    codexHome = codexHome,
                    authSource = CodexAuthSource.CodexLite,
                ),
            )

            coroutineScope {
                val store = FileSystemCodexAuthStore(dataDirectory, settings)
                try {
                    val authFile = readAuthFile(dataDirectory)
                    assertEquals(local.tokens, authFile.tokens)
                    assertEquals(local.lastRefresh, authFile.lastRefresh)

                    writeCodexAuth(codexHome, subscriptionAuth("external"))
                    store.reload()

                    assertEquals(local.tokens?.accessToken, store.authenticated().accessToken)
                } finally {
                    store.close()
                }
            }
        }
    }

    test("global settings switch sources without writing Codex auth json") {
        withAuthDirectories("switch-source") { codexHome, dataDirectory ->
            val codex = subscriptionAuth("codex")
            val local = subscriptionAuth("local")
            writeCodexAuth(codexHome, codex)
            writeAuthFile(dataDirectory, local.toCodexLiteAuthFile())
            val settings = InMemoryCodexGlobalSettings(CodexGlobalSettings(codexHome = codexHome))

            coroutineScope {
                val store = FileSystemCodexAuthStore(dataDirectory, settings)
                try {
                    assertEquals(codex.tokens?.accessToken, store.authenticated().accessToken)

                    settings.update { current -> current.copy(authSource = CodexAuthSource.CodexLite) }
                    assertEquals(
                        local.tokens?.accessToken,
                        store.awaitAuthenticatedAccessToken(
                            requireNotNull(local.tokens).accessToken,
                        ).accessToken,
                    )

                    val external = subscriptionAuth("external")
                    writeCodexAuth(codexHome, external)
                    settings.update { current -> current.copy(authSource = CodexAuthSource.Codex) }
                    assertEquals(
                        external.tokens?.accessToken,
                        store.awaitAuthenticatedAccessToken(
                            requireNotNull(external.tokens).accessToken,
                        ).accessToken,
                    )
                    assertEquals(local.tokens, readAuthFile(dataDirectory).tokens)
                } finally {
                    store.close()
                }
            }
        }
    }

    test("missing Codex Lite credentials publish an unavailable state") {
        withAuthDirectories("missing-local") { codexHome, dataDirectory ->
            val settings = InMemoryCodexGlobalSettings(
                CodexGlobalSettings(
                    codexHome = codexHome,
                    authSource = CodexAuthSource.CodexLite,
                ),
            )

            coroutineScope {
                val store = FileSystemCodexAuthStore(dataDirectory, settings)
                try {
                    assertIs<CodexAuthState.Unavailable>(store.state.value)
                    assertFalse(SystemCoroutineFileSystem.exists(Path(dataDirectory, "auth.yml")))
                } finally {
                    store.close()
                }
            }
        }
    }

    test("Codex Lite refreshes credentials through the OpenAI login client") {
        withAuthDirectories("refresh-local") { codexHome, dataDirectory ->
            val expiredTokens = subscriptionTokens("expired", Clock.System.now() - 1.hours)
            val refreshedTokens = subscriptionTokens("refreshed", Clock.System.now() + 1.days)
            writeAuthFile(
                dataDirectory,
                CodexLiteAuthFile(
                    authMode = CodexAuthMode.Chatgpt,
                    tokens = expiredTokens,
                    lastRefresh = Clock.System.now() - 1.days,
                ),
            )
            val settings = InMemoryCodexGlobalSettings(
                CodexGlobalSettings(
                    codexHome = codexHome,
                    authSource = CodexAuthSource.CodexLite,
                ),
            )
            val loginClient = RecordingOpenAiLoginClient(
                OpenAiSubscriptionTokenRefresh(
                    accessToken = refreshedTokens.accessToken,
                    refreshToken = refreshedTokens.refreshToken,
                ),
            )

            coroutineScope {
                val store = FileSystemCodexAuthStore(
                    dataDirectory = dataDirectory,
                    globalSettings = settings,
                    fileSystem = SystemCoroutineFileSystem,
                    loginClient = loginClient,
                )
                try {
                    store.awaitAuthenticatedAccessToken(refreshedTokens.accessToken)

                    assertEquals(listOf(expiredTokens.refreshToken), loginClient.refreshTokens)
                    val persisted = readAuthFile(dataDirectory)
                    assertEquals(refreshedTokens.accessToken, persisted.tokens.accessToken)
                    assertEquals(refreshedTokens.refreshToken, persisted.tokens.refreshToken)
                    assertEquals(expiredTokens.idToken, persisted.tokens.idToken)
                } finally {
                    store.close()
                }
            }
        }
    }

    test("access token expiry schedules refresh five minutes early") {
        val expiresAt = Clock.System.now() + 3.hours
        val tokens = subscriptionTokens("schedule", expiresAt)

        assertEquals(
            Instant.fromEpochSeconds(expiresAt.epochSeconds) - 5.minutes,
            subscriptionRefreshAt(tokens, Clock.System.now() - 4.days),
        )
    }

    test("opaque access token falls back to the last refresh time") {
        val lastRefresh = Clock.System.now() - 2.days
        val tokens = subscriptionTokens("opaque", Clock.System.now() + 1.days)
            .copy(accessToken = "opaque-access-token")

        assertEquals(lastRefresh + 8.days, subscriptionRefreshAt(tokens, lastRefresh))
    }

    test("PKCE S256 code challenge follows RFC 7636") {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            pkceCodeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }
}

private suspend fun withAuthDirectories(
    label: String,
    block: suspend (codexHome: Path, dataDirectory: Path) -> Unit,
) {
    val root = Path(SystemTemporaryDirectory, "codex-lite-auth-$label-${Random.nextLong()}")
    val codexHome = Path(root, "codex")
    val dataDirectory = Path(root, "codexlite")
    try {
        SystemCoroutineFileSystem.createDirectories(codexHome)
        block(codexHome, dataDirectory)
    } finally {
        deleteRecursively(root)
    }
}

private suspend fun writeCodexAuth(
    codexHome: Path,
    auth: CodexAuthJson,
) {
    SystemCoroutineFileSystem.writeString(
        Path(codexHome, "auth.json"),
        OpenAiJsonCodec.encodeToString(CodexAuthJson.serializer(), auth),
    )
}

private suspend fun writeAuthFile(
    dataDirectory: Path,
    auth: CodexLiteAuthFile,
) {
    SystemCoroutineFileSystem.createDirectories(dataDirectory)
    SystemCoroutineFileSystem.writeString(
        Path(dataDirectory, "auth.yml"),
        AuthYaml.encodeToString(CodexLiteAuthFile.serializer(), auth),
    )
}

private suspend fun readAuthFile(dataDirectory: Path): CodexLiteAuthFile =
    AuthYaml.decodeFromString(
        CodexLiteAuthFile.serializer(),
        SystemCoroutineFileSystem.readString(Path(dataDirectory, "auth.yml")),
    )

private fun CodexAuthJson.toCodexLiteAuthFile(): CodexLiteAuthFile =
    CodexLiteAuthFile(
        authMode = CodexAuthMode.Chatgpt,
        tokens = requireNotNull(tokens),
        lastRefresh = requireNotNull(lastRefresh),
    )

private fun CodexAuthStore.authenticated() =
    assertIs<CodexAuthState.Authenticated>(state.value).value

private suspend fun CodexAuthStore.awaitAuthenticatedAccessToken(accessToken: String) =
    withTimeout(1_000) {
        assertIs<CodexAuthState.Authenticated>(
            state.first { current ->
                (current as? CodexAuthState.Authenticated)?.value?.accessToken == accessToken
            },
        ).value
    }

private fun subscriptionAuth(label: String): CodexAuthJson {
    val refreshedAt = Clock.System.now()
    return CodexAuthJson(
        authMode = CodexAuthMode.Chatgpt,
        tokens = subscriptionTokens(label, refreshedAt + 1.days),
        lastRefresh = refreshedAt,
    )
}

private fun subscriptionTokens(
    label: String,
    expiresAt: Instant,
): OpenAiSubscriptionTokens = OpenAiSubscriptionTokens(
    idToken = jwt(
        expiresAt = expiresAt,
        accountId = "account-$label",
        planType = "plus",
    ),
    accessToken = jwt(expiresAt),
    refreshToken = "refresh-$label",
)

private class RecordingOpenAiLoginClient(
    private val refreshResponse: OpenAiSubscriptionTokenRefresh,
) : OpenAiLoginClient {
    val refreshTokens: MutableList<String> = mutableListOf()

    override fun authorizationUrl(request: io.github.stream29.codex.lite.openai.OpenAiLoginAuthorization): String =
        error("Authorization URL is not used by this test.")

    override suspend fun exchangeAuthorizationCode(
        request: io.github.stream29.codex.lite.openai.OpenAiAuthorizationCodeExchange,
    ): OpenAiSubscriptionTokens = error("Authorization-code exchange is not used by this test.")

    override suspend fun refreshSubscriptionTokens(
        refreshToken: String,
    ): OpenAiSubscriptionTokenRefresh {
        refreshTokens += refreshToken
        return refreshResponse
    }
}

private fun jwt(
    expiresAt: Instant,
    accountId: String = "account",
    planType: String = "plus",
): String {
    val header = buildJsonObject {
        put("alg", "none")
    }
    val payload = buildJsonObject {
        put("exp", expiresAt.epochSeconds)
        put(
            "https://api.openai.com/auth",
            buildJsonObject {
                put("chatgpt_account_id", accountId)
                put("chatgpt_plan_type", planType)
            },
        )
    }
    return listOf(header, payload)
        .joinToString(".") { value ->
            Base64.UrlSafe.encode(value.toString().encodeToByteArray()).trimEnd('=')
        } + "."
}

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { child ->
            deleteRecursively(child)
        }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
