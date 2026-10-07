package io.github.stream29.kodex.cli.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.openai.OpenAiAuthState
import io.github.stream29.kodex.openai.OpenAiResult
import io.github.stream29.kodex.openai.OpenAiSubscriptionTokens
import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient
import io.github.stream29.kodex.openai.codexclistorage.CodexAuthJson
import io.github.stream29.kodex.openai.codexclistorage.CodexAuthMode
import io.github.stream29.kodex.openai.codexclistorage.CodexCliStorage
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.serialization.SerializationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Backend-only authority for the two existing file formats. Source selection
 * is observed, never rewritten by login/remove. The injected OAuth transport
 * belongs to the enclosing backend and is not closed by this store.
 */
public class BackendFileSystemAuthStore internal constructor(
    private val owner: CoroutineScope,
    private val dataDirectory: Path,
    private val codexHome: Path,
    private val selectedSource: StateFlow<KodexAuthSource>,
    private val fileSystem: CoroutineFileSystem,
    private val loginClient: OpenAiLoginClient,
    private val clock: Clock,
    private val waitMaintenance: suspend () -> Unit = { delay(1.minutes) },
) : OpenAiAuthStore, AutoCloseable {
    private val writes = Mutex()
    private val generations = KodexAuthSource.entries.associateWith { 0L }.toMutableMap()
    private val loaded = mutableMapOf<KodexAuthSource, AuthLoadResult>()
    override val state: StateFlow<OpenAiAuthState>
        field = MutableStateFlow<OpenAiAuthState>(OpenAiAuthState.Unavailable.NotLoaded)

    internal suspend fun start() {
        writes.withLock {
            KodexAuthSource.entries.forEach { loaded[it] = read(it) }
            publishSelected()
        }
        owner.launch {
            selectedSource.collect { reload(it) }
        }
        // Both files participate in maintenance even when the other is selected.
        KodexAuthSource.entries.forEach { source ->
            owner.launch {
                while (currentCoroutineContext().isActive) {
                    try {
                        refreshIfDue(source)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Preserve the old file/state. The next pass re-reads the
                        // source; do not retain an unsaved token response.
                        BackendAuthLogger.warn { "Authentication maintenance failed for $source; retrying from its file." }
                    }
                    waitMaintenance()
                }
            }
        }
    }

    /** Internal preparation fence, not a persistent identity or wire handle. */
    public suspend fun beginLogin(source: KodexAuthSource): Long = writes.withLock {
        owner.ensureActive()
        nextGeneration(source)
    }

    /** Saves only the bound source and rejects a login invalidated by remove/new login. */
    public suspend fun commitLogin(
        source: KodexAuthSource,
        generation: Long,
        tokens: OpenAiSubscriptionTokens,
    ) {
        writes.withLock {
            owner.ensureActive()
            currentCoroutineContext().ensureActive()
            check(generations.getValue(source) == generation) { "The credential login is no longer current." }
            val auth = ActiveSubscriptionAuth(tokens, clock.now())
            write(source, auth)
            loaded[source] = AuthLoadResult.Loaded(auth)
            publishSelected()
        }
    }

    public suspend fun remove(source: KodexAuthSource) {
        writes.withLock {
            owner.ensureActive()
            nextGeneration(source)
            fileSystem.delete(path(source), mustExist = false)
            loaded[source] = AuthLoadResult.Unavailable(OpenAiAuthState.Unavailable.CredentialsNotFound)
            publishSelected()
        }
    }

    /** Internal maintenance; no manual reload RPC or frontend polling. */
    public suspend fun reload(source: KodexAuthSource) {
        writes.withLock {
            owner.ensureActive()
            loaded[source] = read(source)
            publishSelected()
        }
    }

    internal suspend fun refreshIfDue(source: KodexAuthSource) {
        writes.withLock {
            owner.ensureActive()
            val result = read(source)
            if (result !is AuthLoadResult.Loaded) {
                loaded[source] = result
                publishSelected()
                return@withLock
            }
            val current = result.auth
            if (subscriptionRefreshAt(current.tokens, current.lastRefresh) > clock.now()) {
                loaded[source] = result
                publishSelected()
                return@withLock
            }
            val refreshedResponse = loginClient.refreshSubscriptionTokens(current.tokens.refreshToken)
            val refreshed = when (refreshedResponse) {
                is OpenAiResult.Success -> current.refreshed(refreshedResponse.value, clock.now())
                is OpenAiResult.Failure -> {
                    BackendAuthLogger.warn {
                        "Authentication refresh failed for $source: ${refreshedResponse.error.message}"
                    }
                    return@withLock
                }
            }
            // An independent Codex process can replace its file while our HTTP
            // request is in flight. Never overwrite an observed newer snapshot.
            val latest = read(source)
            if (latest !is AuthLoadResult.Loaded || latest.auth.tokens != current.tokens) {
                loaded[source] = latest
                publishSelected()
                return@withLock
            }
            write(source, refreshed, preserveCodexMetadata = true)
            loaded[source] = AuthLoadResult.Loaded(refreshed)
            publishSelected()
        }
    }

    override fun close() { owner.cancel() }

    private fun nextGeneration(source: KodexAuthSource): Long {
        val previous = generations.getValue(source)
        check(previous < Long.MAX_VALUE) { "Credential login identities are exhausted." }
        return (previous + 1).also { generations[source] = it }
    }

    private fun publishSelected() {
        state.value = when (val current = loaded[selectedSource.value]) {
            is AuthLoadResult.Loaded -> OpenAiAuthState.Authenticated(current.auth.publicState)
            is AuthLoadResult.Unavailable -> current.reason
            null -> OpenAiAuthState.Unavailable.NotLoaded
        }
    }

    private suspend fun read(source: KodexAuthSource): AuthLoadResult = try {
        when (source) {
            KodexAuthSource.Codex -> CodexCliStorage(codexHome, fileSystem).readAuthOrNull()
                ?.toAuthLoadResult()
                ?: AuthLoadResult.Unavailable(OpenAiAuthState.Unavailable.CredentialsNotFound)
            KodexAuthSource.Kodex -> if (fileSystem.exists(path(source))) {
                AuthYaml.decodeFromString(KodexAuthFile.serializer(), fileSystem.readString(path(source)))
                    .toAuthLoadResult()
            } else AuthLoadResult.Unavailable(OpenAiAuthState.Unavailable.CredentialsNotFound)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SerializationException) {
        AuthLoadResult.Unavailable(OpenAiAuthState.Unavailable.InvalidCredentials)
    } catch (_: IOException) {
        AuthLoadResult.Unavailable(OpenAiAuthState.Unavailable.CredentialSourceUnavailable)
    } catch (_: Exception) {
        AuthLoadResult.Unavailable(OpenAiAuthState.Unavailable.UnexpectedFailure)
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun write(
        source: KodexAuthSource,
        auth: ActiveSubscriptionAuth,
        preserveCodexMetadata: Boolean = false,
    ) {
        val destination = path(source)
        val directory = requireNotNull(destination.parent)
        val text = when (source) {
            KodexAuthSource.Codex -> OpenAiJsonCodec.encodeToString(
                CodexAuthJson.serializer(),
                (if (preserveCodexMetadata) CodexCliStorage(codexHome, fileSystem).readAuthOrNull() else null)
                    ?.copy(tokens = auth.tokens, lastRefresh = auth.lastRefresh)
                    ?: CodexAuthJson(authMode = CodexAuthMode.Chatgpt, tokens = auth.tokens, lastRefresh = auth.lastRefresh),
            )
            KodexAuthSource.Kodex -> AuthYaml.encodeToString(
                KodexAuthFile.serializer(), KodexAuthFile(CodexAuthMode.Chatgpt, auth.tokens, auth.lastRefresh),
            )
        }
        fileSystem.createDirectories(directory)
        val temporary = Path(directory, ".${destination.name}.${Uuid.generateV7()}.tmp")
        var primaryFailure: Throwable? = null
        try {
            fileSystem.writePrivateString(temporary, "$text\n", mustCreate = true)
            fileSystem.atomicMove(temporary, destination)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val cleanupFailure = withContext(NonCancellable) {
                runCatching { fileSystem.delete(temporary, mustExist = false) }.exceptionOrNull()
            }
            if (cleanupFailure != null) {
                val primary = primaryFailure
                if (primary == null) throw cleanupFailure
                if (cleanupFailure !== primary) primary.addSuppressed(cleanupFailure)
            }
        }
    }

    private fun path(source: KodexAuthSource): Path = when (source) {
        KodexAuthSource.Codex -> Path(codexHome, "auth.json")
        KodexAuthSource.Kodex -> Path(dataDirectory, KodexAuthFileName)
    }
}

private val BackendAuthLogger by lazy { KotlinLogging.logger {} }

/** Creates the two-source authority in a child of the backend lifecycle. */
public suspend fun CoroutineScope.BackendFileSystemAuthStore(
    dataDirectory: Path,
    codexHome: Path,
    selectedSource: StateFlow<KodexAuthSource>,
    loginClient: OpenAiLoginClient,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    clock: Clock = Clock.System,
): BackendFileSystemAuthStore {
    val owner = supervisorChildScope()
    return try {
        BackendFileSystemAuthStore(owner, dataDirectory, codexHome, selectedSource, fileSystem, loginClient, clock)
            .also { it.start() }
    } catch (failure: Throwable) {
        owner.cancel()
        throw failure
    }
}
