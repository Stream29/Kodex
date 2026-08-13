package io.github.stream29.kodex.cli.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.cli.settings.KodexGlobalSettings
import io.github.stream29.kodex.cli.settings.KodexGlobalSettingsStore
import io.github.stream29.kodex.openai.OpenAiAuthState
import io.github.stream29.kodex.openai.OpenAiSubscriptionAuthState
import io.github.stream29.kodex.openai.OpenAiSubscriptionTokenRefresh
import io.github.stream29.kodex.openai.OpenAiSubscriptionTokens
import io.github.stream29.kodex.openai.client.OpenAiLoginClient
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient as OpenAiLoginClientContract
import io.github.stream29.kodex.openai.codexclistorage.CodexAuthJson
import io.github.stream29.kodex.openai.codexclistorage.CodexAuthMode
import io.github.stream29.kodex.openai.codexclistorage.CodexCliStorage
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.logging.global
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.serialization.SerializationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val logger by lazy {
    KotlinLogging.logger {}.global()
}

private data class ActiveSubscriptionAuth(
    val tokens: OpenAiSubscriptionTokens,
    val lastRefresh: Instant,
) {
    val publicState: OpenAiSubscriptionAuthState
        get() {
            val claims = tokens.idToken.subscriptionJwtClaims()
            return OpenAiSubscriptionAuthState(
                accessToken = tokens.accessToken,
                accountId = tokens.accountId
                    ?.takeIf(String::isNotBlank)
                    ?: claims.accountId,
                planType = claims.planType,
                email = claims.email,
            )
        }

    fun refreshed(
        response: OpenAiSubscriptionTokenRefresh,
        refreshedAt: Instant,
    ): ActiveSubscriptionAuth = copy(
        tokens = tokens.copy(
            idToken = response.idToken ?: tokens.idToken,
            accessToken = response.accessToken ?: tokens.accessToken,
            refreshToken = response.refreshToken ?: tokens.refreshToken,
        ),
        lastRefresh = refreshedAt,
    )
}

private sealed interface AuthLoadResult {
    data class Loaded(
        val auth: ActiveSubscriptionAuth,
    ) : AuthLoadResult

    data class Unavailable(
        val reason: OpenAiAuthState.Unavailable,
        val failure: Throwable? = null,
    ) : AuthLoadResult
}

@OptIn(ExperimentalCoroutinesApi::class)
private class FileSystemKodexAuthStoreImpl(
    scope: CoroutineScope,
    private val dataDirectory: Path,
    private val globalSettings: KodexGlobalSettingsStore,
    private val fileSystem: CoroutineFileSystem,
    private val loginClient: OpenAiLoginClientContract,
) : KodexAuthStore, CoroutineScope by scope {
    private val updateMutex = Mutex()
    private val loginMutex = Mutex()
    private val maintenanceSignal = Channel<Unit>(Channel.CONFLATED)
    private val activeAuth = MutableStateFlow<ActiveSubscriptionAuth?>(null)
    private var activeLogin: LocalKodexLoginAttempt? = null

    override val state: StateFlow<OpenAiAuthState>
        field = MutableStateFlow<OpenAiAuthState>(
            OpenAiAuthState.Unavailable.NotLoaded,
        )

    init {
        launch(start = CoroutineStart.UNDISPATCHED) {
            globalSettings.settings
                .map { snapshot -> snapshot.authSource to snapshot.codexHome }
                .distinctUntilChanged()
                .drop(1)
                .collect { reload() }
        }
        launch {
            runMaintenanceLoop()
        }
    }

    override suspend fun reload(): Unit = updateMutex.withLock {
        reloadWithinLock()
    }

    private suspend fun reloadWithinLock() {
        try {
            publish(resolveAuth(globalSettings.settings.value))
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            publishUnavailable(
                reason = OpenAiAuthState.Unavailable.UnexpectedFailure,
                failure = failure,
            )
        }
    }

    override fun close() {
        activeLogin?.cancel()
        cancel()
        loginClient.close()
    }

    override suspend fun startKodexLogin(): KodexAuthLoginAttempt = loginMutex.withLock {
        check(activeLogin == null) { "A Kodex browser sign-in is already in progress." }
        val attempt = LocalKodexLoginAttempt.start(
            scope = this,
            loginClient = loginClient,
            persistTokens = ::persistKodexLogin,
            onFinished = { completed ->
                loginMutex.withLock {
                    if (activeLogin === completed) activeLogin = null
                }
            },
        )
        activeLogin = attempt
        attempt
    }

    private suspend fun runMaintenanceLoop() {
        while (currentCoroutineContext().isActive) {
            val active = activeAuth.value
            val wait = when (globalSettings.settings.value.authSource) {
                KodexAuthSource.Codex -> KodexAuthReloadInterval
                KodexAuthSource.Kodex -> active?.let { auth ->
                    subscriptionRefreshAt(auth.tokens, auth.lastRefresh) - Clock.System.now()
                } ?: MaintenanceRetryDelay
            }
            val rescheduled = wait.isPositive() &&
                select {
                    maintenanceSignal.onReceive { true }
                    onTimeout(wait) { false }
                }
            if (rescheduled) continue
            try {
                when (globalSettings.settings.value.authSource) {
                    KodexAuthSource.Codex -> reload()
                    KodexAuthSource.Kodex -> refreshKodexIfDue()
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                logger.warn(failure) { "Authentication maintenance failed." }
                select<Unit> {
                    maintenanceSignal.onReceive {}
                    onTimeout(MaintenanceRetryDelay) {}
                }
            }
        }
    }

    private suspend fun refreshKodexIfDue(): Unit = updateMutex.withLock {
        if (globalSettings.settings.value.authSource != KodexAuthSource.Kodex) {
            reloadWithinLock()
            return@withLock
        }
        val current = when (val result = resolveKodexAuth()) {
            is AuthLoadResult.Loaded -> result.auth
            is AuthLoadResult.Unavailable -> {
                publishUnavailable(result.reason, result.failure)
                return@withLock
            }
        }
        if (subscriptionRefreshAt(current.tokens, current.lastRefresh) > Clock.System.now()) {
            publish(current)
            return@withLock
        }
        val refreshed = current.refreshed(
            response = loginClient.refreshSubscriptionTokens(current.tokens.refreshToken),
            refreshedAt = Clock.System.now(),
        )
        val refreshedFile = KodexAuthFile(
            authMode = CodexAuthMode.Chatgpt,
            tokens = refreshed.tokens,
            lastRefresh = refreshed.lastRefresh,
        )
        writeAuthFile(refreshedFile)
        if (globalSettings.settings.value.authSource == KodexAuthSource.Kodex) {
            publish(refreshed)
        } else {
            reloadWithinLock()
        }
    }

    private suspend fun resolveAuth(settings: KodexGlobalSettings): AuthLoadResult =
        loadAuthCatching {
            when (settings.authSource) {
                KodexAuthSource.Codex ->
                    CodexCliStorage(settings.codexHome, fileSystem)
                        .readAuthOrNull()
                        ?.toAuthLoadResult()
                        ?: AuthLoadResult.Unavailable(
                            OpenAiAuthState.Unavailable.CredentialsNotFound,
                        )

                KodexAuthSource.Kodex -> resolveKodexAuthFile()
            }
        }

    private suspend fun resolveKodexAuth(): AuthLoadResult =
        loadAuthCatching(::resolveKodexAuthFile)

    private suspend fun resolveKodexAuthFile(): AuthLoadResult {
        if (!fileSystem.exists(authPath)) {
            return AuthLoadResult.Unavailable(
                OpenAiAuthState.Unavailable.CredentialsNotFound,
            )
        }
        return readAuthFile().toAuthLoadResult()
    }

    private suspend fun loadAuthCatching(
        load: suspend () -> AuthLoadResult,
    ): AuthLoadResult =
        try {
            load()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: SerializationException) {
            AuthLoadResult.Unavailable(
                reason = OpenAiAuthState.Unavailable.InvalidCredentials,
                failure = failure,
            )
        } catch (failure: IOException) {
            AuthLoadResult.Unavailable(
                reason = OpenAiAuthState.Unavailable.CredentialSourceUnavailable,
                failure = failure,
            )
        } catch (failure: Throwable) {
            AuthLoadResult.Unavailable(
                reason = OpenAiAuthState.Unavailable.UnexpectedFailure,
                failure = failure,
            )
        }

    private suspend fun readAuthFile(): KodexAuthFile =
        AuthYaml.decodeFromString(
            KodexAuthFile.serializer(),
            fileSystem.readString(authPath),
        )

    private suspend fun writeAuthFile(file: KodexAuthFile) {
        fileSystem.createDirectories(dataDirectory)
        writeAtomically(
            destination = authPath,
            contents = AuthYaml.encodeToString(KodexAuthFile.serializer(), file) + "\n",
        )
    }

    private suspend fun persistKodexLogin(tokens: OpenAiSubscriptionTokens) {
        updateMutex.withLock {
            val completed = ActiveSubscriptionAuth(
                tokens = tokens,
                lastRefresh = Clock.System.now(),
            )
            writeAuthFile(
                KodexAuthFile(
                    authMode = CodexAuthMode.Chatgpt,
                    tokens = completed.tokens,
                    lastRefresh = completed.lastRefresh,
                ),
            )
            globalSettings.update { settings -> settings.copy(authSource = KodexAuthSource.Kodex) }
            publish(completed)
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun writeAtomically(
        destination: Path,
        contents: String,
    ) {
        val temporary = Path(destination.parent!!, ".${destination.name}.${Uuid.generateV7()}.tmp")
        try {
            fileSystem.writeString(temporary, contents, mustCreate = true)
            fileSystem.atomicMove(temporary, destination)
        } finally {
            fileSystem.delete(temporary, mustExist = false)
        }
    }

    private fun publish(auth: ActiveSubscriptionAuth) {
        activeAuth.value = auth
        state.value = OpenAiAuthState.Authenticated(auth.publicState)
        maintenanceSignal.trySend(Unit)
    }

    private fun publish(result: AuthLoadResult) {
        when (result) {
            is AuthLoadResult.Loaded -> publish(result.auth)
            is AuthLoadResult.Unavailable ->
                publishUnavailable(result.reason, result.failure)
        }
    }

    private fun publishUnavailable(
        reason: OpenAiAuthState.Unavailable,
        failure: Throwable? = null,
    ) {
        if (failure != null) {
            logger.warn(failure) {
                "Authentication source ${globalSettings.settings.value.authSource} " +
                    "is unavailable ($reason)."
            }
        }
        activeAuth.value = null
        state.value = reason
        maintenanceSignal.trySend(Unit)
    }

    private val authPath: Path = Path(dataDirectory, KodexAuthFileName)
}

/**
 * Opens the private Kodex credential store.
 *
 * Global settings select whether credentials are read from this store or from
 * the selected Codex Home.
 */
public suspend fun CoroutineScope.FileSystemKodexAuthStore(
    dataDirectory: Path,
    globalSettings: KodexGlobalSettingsStore,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): KodexAuthStore =
    FileSystemKodexAuthStore(
        dataDirectory = dataDirectory,
        globalSettings = globalSettings,
        fileSystem = fileSystem,
        loginClient = OpenAiLoginClient(),
    )

internal suspend fun CoroutineScope.FileSystemKodexAuthStore(
    dataDirectory: Path,
    globalSettings: KodexGlobalSettingsStore,
    fileSystem: CoroutineFileSystem,
    loginClient: OpenAiLoginClientContract,
): KodexAuthStore {
    val scope = supervisorChildScope()
    return FileSystemKodexAuthStoreImpl(
        scope = scope,
        dataDirectory = dataDirectory,
        globalSettings = globalSettings,
        fileSystem = fileSystem,
        loginClient = loginClient,
    ).also { store -> store.reload() }
}

private fun CodexAuthJson.toAuthLoadResult(): AuthLoadResult {
    val mode = authMode ?: CodexAuthMode.Chatgpt
    if (mode != CodexAuthMode.Chatgpt && mode != CodexAuthMode.ChatgptAuthTokens) {
        return AuthLoadResult.Unavailable(
            OpenAiAuthState.Unavailable.UnsupportedAuthMode,
        )
    }
    val tokenData = tokens ?: return AuthLoadResult.Unavailable(
        OpenAiAuthState.Unavailable.InvalidCredentials,
    )
    return AuthLoadResult.Loaded(
        ActiveSubscriptionAuth(
            tokens = tokenData,
            lastRefresh = lastRefresh ?: Clock.System.now(),
        ),
    )
}

private fun KodexAuthFile.toAuthLoadResult(): AuthLoadResult {
    if (authMode != CodexAuthMode.Chatgpt) {
        return AuthLoadResult.Unavailable(
            OpenAiAuthState.Unavailable.UnsupportedAuthMode,
        )
    }
    return AuthLoadResult.Loaded(
        ActiveSubscriptionAuth(
            tokens = tokens,
            lastRefresh = lastRefresh,
        ),
    )
}

internal fun subscriptionRefreshAt(
    tokens: OpenAiSubscriptionTokens,
    lastRefresh: Instant,
): Instant {
    val expiresAt = tokens.accessToken.subscriptionJwtClaims().expiresAt
    return expiresAt?.minus(RefreshWindow) ?: lastRefresh + RefreshFallbackInterval
}

private val RefreshWindow: Duration = 5.minutes
private val RefreshFallbackInterval: Duration = 8.days
private val KodexAuthReloadInterval: Duration = 1.minutes
private val MaintenanceRetryDelay: Duration = 1.minutes
