package io.github.stream29.kodex.cli.auth

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient
import io.github.stream29.kodex.openai.codexclistorage.CodexAuthJson
import io.github.stream29.kodex.openai.codexclistorage.CodexAuthMode
import io.github.stream29.kodex.openai.codexclistorage.CodexCliStorage
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.IOException
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

val backendFileSystemAuthStoreTest by testSuite(compartment = { TestCompartment.RealTime }) {
    for (source in KodexAuthSource.entries) {
        for (cancelled in listOf(false, true)) {
            test("$source preserves primary save ${if (cancelled) "cancellation" else "failure"} when cleanup also fails") {
                withBackendAuth { f ->
                    f.login(source, "old")
                    val primary = if (cancelled) CancellationException("Save cancelled") else IOException("Save failed")
                    val cleanup = IOException("Temporary cleanup failed")
                    f.fs.beforeMove = { throw primary }
                    f.fs.deleteFailure = cleanup
                    try {
                        val actual = assertFailsWith<Throwable> { f.login(source, "new") }
                        assertSame(primary, actual)
                        assertEquals(listOf(cleanup), actual.suppressedExceptions)
                        val destination = if (source == KodexAuthSource.Codex) Path(f.codex, "auth.json")
                            else Path(f.data, "auth.yml")
                        assertTrue(f.fs.readString(destination).contains("access-old"))
                    } finally {
                        f.fs.deleteFailure = null
                    }
                }
            }
        }
        test("$source exposes cleanup failure when the credential move succeeded") {
            withBackendAuth { f ->
                val cleanup = IOException("Temporary cleanup failed")
                f.fs.deleteFailure = cleanup
                try {
                    val actual = assertFailsWith<IOException> { f.login(source, "new") }
                    assertSame(cleanup, actual)
                    assertTrue(actual.suppressedExceptions.isEmpty())
                } finally {
                    f.fs.deleteFailure = null
                }
            }
        }
    }
    test("bound login writes each format without changing selection or the other file") {
        withBackendAuth { f ->
            f.login(KodexAuthSource.Codex, "codex")
            f.login(KodexAuthSource.Kodex, "kodex")
            assertEquals("access-codex", assertIs<OpenAiAuthState.Authenticated>(f.store.state.value).credentials.accessToken)
            assertEquals("access-codex", CodexCliStorage(f.codex).readAuthOrNull()?.tokens?.accessToken)
            assertEquals("access-kodex", AuthYaml.decodeFromString(KodexAuthFile.serializer(),
                f.fs.readString(Path(f.data, "auth.yml"))).tokens.accessToken)
            f.selected.value = KodexAuthSource.Kodex
            val state = f.store.state.first { (it as? OpenAiAuthState.Authenticated)?.credentials?.accountId == "account-kodex" }
            assertEquals("access-kodex", assertIs<OpenAiAuthState.Authenticated>(state).credentials.accessToken)
            f.store.remove(KodexAuthSource.Codex)
            assertFalse(f.fs.exists(Path(f.codex, "auth.json")))
            assertTrue(f.fs.exists(Path(f.data, "auth.yml")))
            assertEquals(KodexAuthSource.Kodex, f.selected.value)
        }
    }
    test("remove and newer preparation invalidate old login commits") {
        withBackendAuth { f ->
            for (source in KodexAuthSource.entries) {
                val old = f.store.beginLogin(source)
                f.store.remove(source)
                assertFailsWith<IllegalStateException> { f.store.commitLogin(source, old, tokens("late")) }
                val superseded = f.store.beginLogin(source)
                val fresh = f.store.beginLogin(source)
                assertFailsWith<IllegalStateException> { f.store.commitLogin(source, superseded, tokens("late")) }
                f.store.commitLogin(source, fresh, tokens(source.name))
            }
        }
    }
    test("both sources refresh and persist including the unselected source") {
        withBackendAuth { f ->
            f.login(KodexAuthSource.Codex, "codex")
            f.login(KodexAuthSource.Kodex, "kodex")
            f.clock.instant += 9.days
            KodexAuthSource.entries.forEach { f.store.refreshIfDue(it) }
            assertEquals(setOf("refresh-codex", "refresh-kodex"), f.client.refreshed.toSet())
            assertEquals("rotated-refresh-codex", CodexCliStorage(f.codex).readAuthOrNull()?.tokens?.accessToken)
            val privateFile = AuthYaml.decodeFromString(KodexAuthFile.serializer(), f.fs.readString(Path(f.data, "auth.yml")))
            assertEquals("rotated-refresh-kodex", privateFile.tokens.accessToken)
            assertEquals(KodexAuthSource.Codex, f.selected.value)
        }
    }
    test("both sources retain their persisted credentials when refresh fails and retry from the file") {
        withBackendAuth { f ->
            f.login(KodexAuthSource.Codex, "codex")
            f.login(KodexAuthSource.Kodex, "kodex")
            f.clock.instant += 9.days
            f.client.failure = OpenAiLoginError(400, "Bad Request", "Synthetic refresh failure")
            for (source in KodexAuthSource.entries) {
                val destination = if (source == KodexAuthSource.Codex) Path(f.codex, "auth.json") else Path(f.data, "auth.yml")
                val before = f.fs.readString(destination)
                f.store.refreshIfDue(source)
                assertEquals(before, f.fs.readString(destination))
            }
            assertEquals("access-codex", assertIs<OpenAiAuthState.Authenticated>(f.store.state.value).credentials.accessToken)
            f.client.failure = null
            KodexAuthSource.entries.forEach { f.store.refreshIfDue(it) }
            assertEquals(listOf("refresh-codex", "refresh-kodex", "refresh-codex", "refresh-kodex"), f.client.refreshed)
        }
    }
    test("Kodex save failure preserves the old state and rereads the old refresh token") {
        withBackendAuth { f ->
            f.login(KodexAuthSource.Kodex, "old")
            f.selected.value = KodexAuthSource.Kodex
            f.store.state.first { (it as? OpenAiAuthState.Authenticated)?.credentials?.accountId == "account-old" }
            f.clock.instant += 9.days
            val original = f.fs.readString(Path(f.data, "auth.yml"))
            f.fs.failMove = true
            assertFailsWith<IllegalStateException> { f.store.refreshIfDue(KodexAuthSource.Kodex) }
            assertEquals(original, f.fs.readString(Path(f.data, "auth.yml")))
            assertEquals("access-old", assertIs<OpenAiAuthState.Authenticated>(f.store.state.value).credentials.accessToken)
            assertTrue(f.fs.list(f.data).none { it.name.endsWith(".tmp") })
            f.fs.failMove = false
            f.store.refreshIfDue(KodexAuthSource.Kodex)
            assertEquals(listOf("refresh-old", "refresh-old"), f.client.refreshed)
            assertEquals("rotated-refresh-old", assertIs<OpenAiAuthState.Authenticated>(f.store.state.value).credentials.accessToken)
        }
    }
    test("backend maintenance refreshes both files with no frontend owner or reload calls") {
        coroutineScope {
            val root = Path(SystemTemporaryDirectory, "kodex-auth-maintenance-${Random.nextLong()}")
            val client = BackendAuthTestLoginClient()
            val clock = BackendAuthTestClock()
            val reachedWait = Channel<Unit>(2)
            val owner = supervisorChildScope()
            val store = BackendFileSystemAuthStore(
                owner, Path(root, "kodex"), Path(root, "codex"),
                MutableStateFlow(KodexAuthSource.Codex), SystemCoroutineFileSystem, client, clock,
                waitMaintenance = { reachedWait.send(Unit); awaitCancellation() },
            )
            try {
                SystemCoroutineFileSystem.createDirectories(Path(root, "codex"))
                SystemCoroutineFileSystem.createDirectories(Path(root, "kodex"))
                SystemCoroutineFileSystem.writeString(Path(root, "codex", "auth.json"), OpenAiJsonCodec.encodeToString(
                    CodexAuthJson.serializer(), CodexAuthJson(
                        authMode = CodexAuthMode.Chatgpt, tokens = tokens("Codex"), lastRefresh = clock.now() - 9.days,
                    ),
                ))
                SystemCoroutineFileSystem.writeString(Path(root, "kodex", "auth.yml"), AuthYaml.encodeToString(
                    KodexAuthFile.serializer(), KodexAuthFile(CodexAuthMode.Chatgpt, tokens("Kodex"), clock.now() - 9.days),
                ))
                store.start()
                repeat(2) { reachedWait.receive() }
                assertEquals(setOf("refresh-Codex", "refresh-Kodex"), client.refreshed.toSet())
                assertEquals("rotated-refresh-Codex", CodexCliStorage(Path(root, "codex")).readAuthOrNull()?.tokens?.accessToken)
                assertEquals("rotated-refresh-Kodex", AuthYaml.decodeFromString(
                    KodexAuthFile.serializer(), SystemCoroutineFileSystem.readString(Path(root, "kodex", "auth.yml")),
                ).tokens.accessToken)
            } finally {
                store.close()
                withContext(NonCancellable) {
                    owner.coroutineContext.job.cancelAndJoin()
                    reachedWait.close()
                    removeAuthTestTree(root)
                }
            }
            assertFalse(client.closed)
        }
    }
    test("a newer external Codex file defeats an in-flight refresh write") {
        withBackendAuth { f ->
            f.login(KodexAuthSource.Codex, "old")
            f.clock.instant += 9.days
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.client.refresh = { entered.complete(Unit); release.await(); OpenAiSubscriptionTokenRefresh(accessToken = "stale") }
            val task = async { f.store.refreshIfDue(KodexAuthSource.Codex) }
            entered.await()
            f.fs.writeString(Path(f.codex, "auth.json"), OpenAiJsonCodec.encodeToString(
                CodexAuthJson.serializer(), CodexAuthJson(
                    authMode = CodexAuthMode.Chatgpt, tokens = tokens("external"), lastRefresh = f.clock.now(),
                )))
            release.complete(Unit)
            task.await()
            assertEquals("access-external", CodexCliStorage(f.codex).readAuthOrNull()?.tokens?.accessToken)
            assertEquals("access-external", assertIs<OpenAiAuthState.Authenticated>(f.store.state.value).credentials.accessToken)
        }
    }
    test("remove waits for prior refresh and no later write restores the deleted file") {
        withBackendAuth { f ->
            f.login(KodexAuthSource.Codex, "old")
            f.clock.instant += 9.days
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.client.refresh = { entered.complete(Unit); release.await(); OpenAiSubscriptionTokenRefresh(accessToken = "rotated") }
            val refresh = async { f.store.refreshIfDue(KodexAuthSource.Codex) }
            entered.await()
            val remove = async(start = CoroutineStart.UNDISPATCHED) { f.store.remove(KodexAuthSource.Codex) }
            assertFalse(remove.isCompleted)
            release.complete(Unit)
            refresh.await()
            remove.await()
            assertFalse(f.fs.exists(Path(f.codex, "auth.json")))
            f.store.refreshIfDue(KodexAuthSource.Codex)
            assertFalse(f.fs.exists(Path(f.codex, "auth.json")))
            assertEquals(OpenAiAuthState.Unavailable.CredentialsNotFound, f.store.state.value)
        }
    }
    test("save failure does not publish or retain an unsaved token result") {
        withBackendAuth { f ->
            f.login(KodexAuthSource.Codex, "old")
            f.client.refresh = {
                OpenAiSubscriptionTokenRefresh(accessToken = "rotated", refreshToken = "new-refresh")
            }
            f.fs.failMove = true
            f.clock.instant += 9.days
            assertFailsWith<IllegalStateException> { f.store.refreshIfDue(KodexAuthSource.Codex) }
            assertEquals("access-old", assertIs<OpenAiAuthState.Authenticated>(f.store.state.value).credentials.accessToken)
            assertEquals("access-old", CodexCliStorage(f.codex).readAuthOrNull()?.tokens?.accessToken)
            f.fs.failMove = false
            f.store.refreshIfDue(KodexAuthSource.Codex)
            // The initial maintenance pass may also have reached the now-due
            // source. Every failed save must still retry from the old file.
            assertTrue(f.client.refreshed.size >= 2)
            assertTrue(f.client.refreshed.all { it == "refresh-old" })
            assertTrue(f.fs.list(f.codex).none { it.name.endsWith(".tmp") })
        }
    }
    test("cancelling persistence removes its temporary file and preserves the previous snapshot") {
        withBackendAuth { f ->
            f.login(KodexAuthSource.Kodex, "old")
            val entered = CompletableDeferred<Unit>()
            f.fs.beforeMove = { entered.complete(Unit); kotlinx.coroutines.awaitCancellation() }
            val pending = launch { f.login(KodexAuthSource.Kodex, "new") }
            entered.await()
            pending.cancelAndJoin()
            assertTrue(f.fs.list(f.data).none { it.name.endsWith(".tmp") })
            val old = AuthYaml.decodeFromString(KodexAuthFile.serializer(), f.fs.readString(Path(f.data, "auth.yml")))
            assertEquals("access-old", old.tokens.accessToken)
        }
    }
    test("malformed credentials stay unavailable without default writes or deletion") {
        withBackendAuth { f ->
            f.fs.createDirectories(f.codex)
            val malformed = "{broken"
            f.fs.writeString(Path(f.codex, "auth.json"), malformed)
            f.store.reload(KodexAuthSource.Codex)
            assertEquals(OpenAiAuthState.Unavailable.InvalidCredentials, f.store.state.value)
            assertEquals(malformed, f.fs.readString(Path(f.codex, "auth.json")))
            assertFalse(f.fs.exists(Path(f.data, "auth.yml")))
        }
    }
    test("refresh preserves the Codex file mode while login remains bound to its selected destination") {
        withBackendAuth { f ->
            f.fs.createDirectories(f.codex)
            val old = CodexAuthJson(authMode = CodexAuthMode.ChatgptAuthTokens, tokens = tokens("old"),
                lastRefresh = f.clock.now() - 9.days)
            f.fs.writeString(Path(f.codex, "auth.json"), OpenAiJsonCodec.encodeToString(CodexAuthJson.serializer(), old))
            f.store.refreshIfDue(KodexAuthSource.Codex)
            assertEquals(CodexAuthMode.ChatgptAuthTokens, CodexCliStorage(f.codex).readAuthOrNull()?.authMode)
            val generation = f.store.beginLogin(KodexAuthSource.Kodex)
            f.selected.value = KodexAuthSource.Codex
            f.store.commitLogin(KodexAuthSource.Kodex, generation, tokens("new"))
            assertEquals("rotated-refresh-old", CodexCliStorage(f.codex).readAuthOrNull()?.tokens?.accessToken)
        }
    }
    test("prepared OpenAI login retains private PKCE across the bound code exchange") {
        val client = BackendAuthTestLoginClient()
        val prepared = prepareOpenAiLogin(client, "http://localhost:1455/auth/callback")
        val request = requireNotNull(client.authorization)
        assertEquals(prepared.state, request.state)
        prepared.exchangeCode("selected-code")
        val exchange = requireNotNull(client.exchange)
        assertEquals("selected-code", exchange.authorizationCode)
        assertEquals(request.codeChallenge, pkceCodeChallenge(exchange.codeVerifier))
        assertEquals(prepared.redirectUri, exchange.redirectUri)
    }
}

private suspend fun withBackendAuth(block: suspend CoroutineScope.(BackendAuthFixture) -> Unit) = coroutineScope {
    val root = Path(SystemTemporaryDirectory, "kodex-backend-auth-${Random.nextLong()}")
    val fs = BackendAuthTestFileSystem()
    val clock = BackendAuthTestClock()
    val client = BackendAuthTestLoginClient()
    val selected = MutableStateFlow(KodexAuthSource.Codex)
    val data = Path(root, "kodex")
    val codex = Path(root, "codex")
    try {
        coroutineScope {
            val initialPasses = Channel<Unit>(2)
            val store = BackendFileSystemAuthStore(
                supervisorChildScope(), data, codex, selected, fs, client, clock,
                waitMaintenance = { initialPasses.send(Unit); awaitCancellation() },
            )
            try {
                store.start()
                repeat(2) { initialPasses.receive() }
                block(BackendAuthFixture(store, selected, client, clock, fs, data, codex))
            } finally {
                initialPasses.close()
                store.close()
            }
        }
    } finally { removeAuthTestTree(root) }
    assertFalse(client.closed)
}

private class BackendAuthFixture(
    val store: BackendFileSystemAuthStore,
    val selected: MutableStateFlow<KodexAuthSource>,
    val client: BackendAuthTestLoginClient,
    val clock: BackendAuthTestClock,
    val fs: BackendAuthTestFileSystem,
    val data: Path,
    val codex: Path,
) {
    suspend fun login(source: KodexAuthSource, value: String) =
        store.commitLogin(source, store.beginLogin(source), tokens(value))
}
private class BackendAuthTestClock : Clock {
    var instant = Instant.parse("2030-01-01T00:00:00Z")
    override fun now() = instant
}
private class BackendAuthTestLoginClient : OpenAiLoginClient {
    val refreshed = mutableListOf<String>()
    var closed = false
    var authorization: OpenAiLoginAuthorization? = null
    var exchange: OpenAiAuthorizationCodeExchange? = null
    var failure: OpenAiLoginError? = null
    var refresh: suspend (String) -> OpenAiSubscriptionTokenRefresh = {
        OpenAiSubscriptionTokenRefresh(accessToken = "rotated-$it")
    }
    override fun authorizationUrl(request: OpenAiLoginAuthorization): String {
        authorization = request
        return "https://authorization.example.test"
    }
    override suspend fun exchangeAuthorizationCode(
        request: OpenAiAuthorizationCodeExchange,
    ): OpenAiLoginResult<OpenAiSubscriptionTokens> {
        exchange = request
        return OpenAiResult.Success(tokens("login"))
    }
    override suspend fun refreshSubscriptionTokens(
        refreshToken: String,
    ): OpenAiLoginResult<OpenAiSubscriptionTokenRefresh> {
        refreshed += refreshToken
        failure?.let { return OpenAiResult.Failure(it) }
        return OpenAiResult.Success(refresh(refreshToken))
    }
    override fun close() { closed = true }
}
private class BackendAuthTestFileSystem : CoroutineFileSystem by SystemCoroutineFileSystem {
    var failMove = false
    var deleteFailure: Throwable? = null
    var beforeMove: suspend () -> Unit = {}
    override suspend fun atomicMove(source: Path, destination: Path) {
        beforeMove()
        if (failMove) error("Synthetic credential save failure.")
        SystemCoroutineFileSystem.atomicMove(source, destination)
    }
    override suspend fun delete(path: Path, mustExist: Boolean) {
        if (path.name.endsWith(".tmp")) deleteFailure?.let { throw it }
        SystemCoroutineFileSystem.delete(path, mustExist)
    }
}
private fun tokens(suffix: String) =
    OpenAiSubscriptionTokens("opaque-id", "access-$suffix", "refresh-$suffix", "account-$suffix")

private suspend fun removeAuthTestTree(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) SystemCoroutineFileSystem.list(path).forEach { removeAuthTestTree(it) }
    SystemCoroutineFileSystem.delete(path)
}
