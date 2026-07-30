package io.github.stream29.codex.lite.cli.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.codex.lite.cli.settings.CodexAuthSource
import io.github.stream29.codex.lite.cli.settings.CodexGlobalSettings
import io.github.stream29.codex.lite.cli.settings.CodexGlobalSettingsStore
import io.github.stream29.codex.lite.openai.OpenAiSubscriptionAuthState
import io.github.stream29.codex.lite.openai.OpenAiSubscriptionTokenRefresh
import io.github.stream29.codex.lite.openai.OpenAiSubscriptionTokens
import io.github.stream29.codex.lite.openai.client.OpenAiLoginClient
import io.github.stream29.codex.lite.openai.client.contract.OpenAiLoginClient as OpenAiLoginClientContract
import io.github.stream29.codex.lite.openai.codexclistorage.CodexAuthJson
import io.github.stream29.codex.lite.openai.codexclistorage.CodexAuthMode
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliStorage
import io.github.stream29.codex.lite.utils.coroutines.supervisorChildScope
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
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
import kotlinx.io.files.Path
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

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

@OptIn(ExperimentalCoroutinesApi::class)
private class FileSystemCodexAuthStoreImpl(
    scope: CoroutineScope,
    private val dataDirectory: Path,
    private val globalSettings: CodexGlobalSettingsStore,
    private val fileSystem: CoroutineFileSystem,
    private val loginClient: OpenAiLoginClientContract,
) : CodexAuthStore, CoroutineScope by scope {
    private val updateMutex = Mutex()
    private val loginMutex = Mutex()
    private val maintenanceSignal = Channel<Unit>(Channel.CONFLATED)
    private val activeAuth = MutableStateFlow<ActiveSubscriptionAuth?>(null)
    private var activeLogin: LocalCodexLiteLoginAttempt? = null

    override val state: StateFlow<CodexAuthState>
        field = MutableStateFlow<CodexAuthState>(
            CodexAuthState.Unavailable("Authentication has not been loaded."),
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
            publishUnavailable(failure)
        }
    }

    override fun close() {
        activeLogin?.cancel()
        cancel()
        loginClient.close()
    }

    override suspend fun startCodexLiteLogin(): CodexLiteAuthLoginAttempt = loginMutex.withLock {
        check(activeLogin == null) { "A Codex Lite browser sign-in is already in progress." }
        val attempt = LocalCodexLiteLoginAttempt.start(
            scope = this,
            loginClient = loginClient,
            persistTokens = ::persistCodexLiteLogin,
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
                CodexAuthSource.Codex -> CodexAuthReloadInterval
                CodexAuthSource.CodexLite -> active?.let { auth ->
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
                    CodexAuthSource.Codex -> reload()
                    CodexAuthSource.CodexLite -> refreshCodexLiteIfDue()
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

    private suspend fun refreshCodexLiteIfDue(): Unit = updateMutex.withLock {
        if (globalSettings.settings.value.authSource != CodexAuthSource.CodexLite) {
            reloadWithinLock()
            return@withLock
        }
        val current = try {
            readAuthFile().toActiveAuth()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            publishUnavailable(failure)
            return@withLock
        }
        if (subscriptionRefreshAt(current.tokens, current.lastRefresh) > Clock.System.now()) {
            publish(current)
            return@withLock
        }
        val refreshed = current.refreshed(
            response = loginClient.refreshSubscriptionTokens(current.tokens.refreshToken),
            refreshedAt = Clock.System.now(),
        )
        val refreshedFile = CodexLiteAuthFile(
            authMode = CodexAuthMode.Chatgpt,
            tokens = refreshed.tokens,
            lastRefresh = refreshed.lastRefresh,
        )
        writeAuthFile(refreshedFile)
        if (globalSettings.settings.value.authSource == CodexAuthSource.CodexLite) {
            publish(refreshed)
        } else {
            reloadWithinLock()
        }
    }

    private suspend fun resolveAuth(settings: CodexGlobalSettings): ActiveSubscriptionAuth =
        when (settings.authSource) {
            CodexAuthSource.Codex -> readCodexAuth(settings.codexHome)
            CodexAuthSource.CodexLite -> readAuthFile().toActiveAuth()
        }

    private suspend fun readCodexAuth(codexHome: Path): ActiveSubscriptionAuth =
        CodexCliStorage(codexHome, fileSystem).readActiveSubscriptionAuth()

    private suspend fun readAuthFile(): CodexLiteAuthFile =
        AuthYaml.decodeFromString(
            CodexLiteAuthFile.serializer(),
            fileSystem.readString(authPath),
        )

    private suspend fun writeAuthFile(file: CodexLiteAuthFile) {
        fileSystem.createDirectories(dataDirectory)
        writeAtomically(
            destination = authPath,
            contents = AuthYaml.encodeToString(CodexLiteAuthFile.serializer(), file) + "\n",
        )
    }

    private suspend fun persistCodexLiteLogin(tokens: OpenAiSubscriptionTokens) {
        updateMutex.withLock {
            val completed = ActiveSubscriptionAuth(
                tokens = tokens,
                lastRefresh = Clock.System.now(),
            )
            writeAuthFile(
                CodexLiteAuthFile(
                    authMode = CodexAuthMode.Chatgpt,
                    tokens = completed.tokens,
                    lastRefresh = completed.lastRefresh,
                ),
            )
            globalSettings.update { settings -> settings.copy(authSource = CodexAuthSource.CodexLite) }
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
        state.value = CodexAuthState.Authenticated(auth.publicState)
        maintenanceSignal.trySend(Unit)
    }

    private fun publishUnavailable(failure: Throwable) {
        activeAuth.value = null
        state.value = CodexAuthState.Unavailable(
            failure.message ?: "The selected authentication source is unavailable.",
        )
        maintenanceSignal.trySend(Unit)
    }

    private val authPath: Path = Path(dataDirectory, CodexLiteAuthFileName)
}

/**
 * Opens the private Codex Lite credential store.
 *
 * Global settings select whether credentials are read from this store or from
 * the selected Codex Home.
 */
public suspend fun CoroutineScope.FileSystemCodexAuthStore(
    dataDirectory: Path,
    globalSettings: CodexGlobalSettingsStore,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): CodexAuthStore =
    FileSystemCodexAuthStore(
        dataDirectory = dataDirectory,
        globalSettings = globalSettings,
        fileSystem = fileSystem,
        loginClient = OpenAiLoginClient(),
    )

internal suspend fun CoroutineScope.FileSystemCodexAuthStore(
    dataDirectory: Path,
    globalSettings: CodexGlobalSettingsStore,
    fileSystem: CoroutineFileSystem,
    loginClient: OpenAiLoginClientContract,
): CodexAuthStore {
    val scope = supervisorChildScope()
    return FileSystemCodexAuthStoreImpl(
        scope = scope,
        dataDirectory = dataDirectory,
        globalSettings = globalSettings,
        fileSystem = fileSystem,
        loginClient = loginClient,
    ).also { store -> store.reload() }
}

private fun CodexAuthJson.toActiveAuth(): ActiveSubscriptionAuth {
    val mode = authMode ?: CodexAuthMode.Chatgpt
    require(mode == CodexAuthMode.Chatgpt || mode == CodexAuthMode.ChatgptAuthTokens) {
        "Subscription auth requires Codex auth mode chatgpt or chatgptAuthTokens, but found $mode."
    }
    val tokenData = requireNotNull(tokens) {
        "Subscription auth requires a complete token object."
    }
    return ActiveSubscriptionAuth(
        tokens = tokenData,
        lastRefresh = lastRefresh ?: Clock.System.now(),
    )
}

private suspend fun CodexCliStorage.readActiveSubscriptionAuth(): ActiveSubscriptionAuth =
    requireNotNull(readAuthOrNull()) {
        "Codex CLI auth.json is required when global settings select Codex authentication."
    }.toActiveAuth()

private fun CodexLiteAuthFile.toActiveAuth(): ActiveSubscriptionAuth {
    require(authMode == CodexAuthMode.Chatgpt) {
        "Codex Lite-managed subscription auth requires auth_mode: chatgpt, but found $authMode."
    }
    return ActiveSubscriptionAuth(
        tokens = tokens,
        lastRefresh = lastRefresh,
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
private val CodexAuthReloadInterval: Duration = 1.minutes
private val MaintenanceRetryDelay: Duration = 1.minutes
