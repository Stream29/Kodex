package io.github.stream29.kodex.cli.app

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.app.application.contract.*
import io.github.stream29.kodex.app.pathpicker.createDirectoryPickerViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModelArguments
import io.github.stream29.kodex.app.sessioncatalog.DefaultSessionCatalogViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModelFactory
import io.github.stream29.kodex.app.settings.createOpenAiLoginViewModel
import io.github.stream29.kodex.app.settings.createSessionSettingsViewModel
import io.github.stream29.kodex.app.settings.createSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.cli.newsession.DEFAULT_NEW_SESSION_NAME
import io.github.stream29.kodex.cli.newsession.DefaultNewSessionViewModelFactory
import io.github.stream29.kodex.cli.newsession.RpcNewSessionViewModel
import io.github.stream29.kodex.cli.notification.collectNotificationHooks
import io.github.stream29.kodex.cli.rpc.*
import io.github.stream29.kodex.cli.session.DefaultPersistedSessionViewModelRegistry
import io.github.stream29.kodex.cli.session.RpcPersistedSessionViewModel
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.Reasoning
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.models.OAuthTarget
import io.github.stream29.kodex.rpc.local.FrontendRpcConnection
import io.github.stream29.kodex.rpc.local.BackendConnectionState
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path

/** Only local frontend presentation is passed to the renderer. */
public class KodexApplication internal constructor(
    public val viewModel: ApplicationViewModel,
    public val newLineKey: StateFlow<NewLineKey>,
    public val sidebarSettings: SidebarSettingsViewModel,
    /**
     * The actual frontend host outlet for unhandled non-cancellation operations.
     * Logs the original failure and offers one local Hook observation to this
     * Application's existing frontend consumer. Return is not Hook completion.
     * Delivery is live and best-effort, with no replay and 64 buffered messages;
     * a slow consumer drops the oldest buffered observation.
     * Cancellation is not reported. Neither a backend Agent Stop nor a new RPC call
     * is produced; closing the Application cancels pending local observations.
     */
    public val reportUnhandledError: (Throwable) -> Unit,
    /**
     * Reports an actual frontend warning through the same Error Hook selection and
     * `{"type":"unhandled_error","message":...}` input as [reportUnhandledError].
     * Logs at warning severity, without creating an inline notice or a backend Stop.
     * Shares the same live, best-effort buffer and single consumer.
     */
    public val reportWarning: (String) -> Unit,
    public val connectionState: StateFlow<BackendConnectionState>,
    public val connectionFailureReason: StateFlow<String?>,
)

/** Actual production frontend assembly; no prepared Home handle or backend resource is owned here. */
public suspend fun <R> withFrontendApplication(
    connection: FrontendRpcConnection,
    workingDirectory: Path,
    applicationWidth: Int? = null,
    render: suspend CoroutineScope.(KodexApplication) -> R,
): R = coroutineScope {
                val cwd = SystemCoroutineFileSystem.resolve(workingDirectory)
                val owner = Job(coroutineContext[Job])
                val frontendScope = CoroutineScope(coroutineContext + owner)
                var application: ApplicationViewModel? = null
                var views: RpcSessionViews? = null
                var global: RpcGlobalSettings? = null
                var failure: Throwable? = null
                try {
                    val services = RpcServices(RestoringRpcClient(connection))
                    val unhandledErrors = MutableSharedFlow<String?>(
                        extraBufferCapacity = 64,
                        onBufferOverflow = BufferOverflow.DROP_OLDEST,
                    )
                    val reportUnhandledError: (Throwable) -> Unit = { error ->
                        if (error !is CancellationException) {
                            ApplicationLogger.error(error) { "Application operation failed (cwd=$cwd)." }
                            unhandledErrors.tryEmit(error.message)
                        }
                    }
                    val reportWarning: (String) -> Unit = { message ->
                        ApplicationLogger.warn { "Application warning (cwd=$cwd): $message" }
                        unhandledErrors.tryEmit(message)
                    }
                    val settings = RpcGlobalSettings.open(services.global, frontendScope, applicationWidth ?: 0)
                        .also { global = it }
                    lateinit var root: ApplicationViewModel
                    val sessionViews = RpcSessionViews(
                        frontendScope, services, settings.models, reportUnhandledError,
                        onCreated = { created ->
                        frontendScope.launch {
                            try { root.openCreatedSessions(created.map { it.sessionIndex }) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Throwable) { ApplicationLogger.error(error) { "Unable to open created Session tabs." } }
                        }
                        },
                        connectionAvailable = { connection.available },
                    ).also { views = it }
                    connection.onReconnection { client, isConnected ->
                        sessionViews.reactivate(RpcServices(RestoringRpcClient(client)), isConnected)
                    }
                    val sessions = DefaultPersistedSessionViewModelRegistry(sessionViews, settings.models, frontendScope)
                    val draftFactory = DefaultNewSessionViewModelFactory(sessionViews, sessions, settings.models, frontendScope)
                    val directoryPicker = { path: Path -> createDirectoryPickerViewModel(path, frontendScope) }
                    root = createApplicationViewModel(
                        sessions, draftFactory,
                        SessionCatalogViewModelFactory { dependencies, interactions ->
                            DefaultSessionCatalogViewModel(frontendScope, dependencies, interactions)
                        },
                        catalogDependencies = RpcSessionCatalogDependencies(services.global),
                        SettingsViewModelFactory { arguments ->
                            val source = when (val target = arguments.target) {
                                is RpcPersistedSessionViewModel -> RpcSessionSettingsSource(target.view, frontendScope)
                                is RpcNewSessionViewModel -> RpcDraftSettingsSource(target.draft, frontendScope)
                                else -> error("Unknown frontend Session view.")
                            }
                            createSettingsViewModel(
                                arguments.initialPage, RpcGlobalEditor(settings, services.global, frontendScope),
                                createSessionSettingsViewModel(
                                    io.github.stream29.kodex.app.settings.contract.SessionSettingsDependencies(
                                        source, settings.models, directoryPicker,
                                    ),
                                    frontendScope,
                                ),
                                RpcNewSessionSettings(settings, frontendScope),
                            )
                        },
                        OpenAiLoginViewModelFactory {
                            val target = OAuthTarget.OpenAi(settings.settings.value.authSource)
                            createOpenAiLoginViewModel(
                                dependencies = OpenAiLoginDependencies {
                                    startRpcOAuth(services.global, target, frontendScope)
                                },
                                ownerScope = frontendScope,
                            )
                        },
                        directoryPicker,
                        { ordinal ->
                            val defaults = settings.settings.value.newSession
                            NewSessionViewModelArguments(
                                if (ordinal == 1) DEFAULT_NEW_SESSION_NAME else "$DEFAULT_NEW_SESSION_NAME $ordinal",
                                KodexAgentSettings(model = defaults.model, cwd = cwd,
                                    reasoning = Reasoning(effort = defaults.reasoningEffort), serviceTier = defaults.serviceTier,
                                    requestUserInputMode = defaults.requestUserInputMode),
                            )
                        },
                        frontendScope,
                    )
                    application = root
                    frontendScope.launch {
                        try {
                            collectNotificationHooks(
                                emptyFlow(),
                                { settings.frontend.settings.value.hooks }, cwd, unhandledErrors,
                            )
                        }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Throwable) { ApplicationLogger.error(error) { "Notification subscription ended." } }
                    }
                    val result = KodexApplication(
                        root, settings.frontend.settings.map { it.newLineKey }.stateIn(
                            frontendScope, SharingStarted.Eagerly, settings.frontend.settings.value.newLineKey,
                        ),
                        createSidebarSettingsViewModel(settings, frontendScope, applicationWidth != null),
                        reportUnhandledError,
                        reportWarning,
                        connection.state,
                        connection.failureReason,
                    )
                    render(result)
                } catch (error: Throwable) {
                    failure = error
                    throw error
                } finally {
                    withContext(NonCancellable) {
                        val original = failure
                        suspend fun cleanup(action: suspend () -> Unit) {
                            try { action() } catch (error: Throwable) {
                                if (failure == null) failure = error else failure.addSuppressed(error)
                            }
                        }
                        cleanup { application?.close() }
                        cleanup { views?.close() }
                        cleanup { global?.close() }
                        cleanup { owner.cancelAndJoin() }
                        cleanup { application?.shutdown() }
                        if (original == null) failure?.let { throw it }
                    }
                }
}

private val ApplicationLogger = KotlinLogging.logger {}
