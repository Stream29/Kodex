package io.github.stream29.kodex.cli.app

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.app.application.contract.*
import io.github.stream29.kodex.app.migration.CurrentKodexApplicationVersion
import io.github.stream29.kodex.app.migration.KodexHomeHandle
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
import io.github.stream29.kodex.cli.settings.openCliFrontendSettings
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.Reasoning
import io.github.stream29.kodex.openai.client.OpenAiClient
import io.github.stream29.kodex.openai.client.OpenAiLoginClient
import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import io.github.stream29.kodex.openai.client.contract.OpenAiClient as ModelClient
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient as LoginClient
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.inmemory.withInMemoryRpc
import io.github.stream29.kodex.rpc.models.OAuthTarget
import io.github.stream29.kodex.rpc.server.withBackendServices
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.osenvironment.requireUserHomeDirectory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path

/** Only local frontend presentation is passed to the renderer. */
public class KodexApplication internal constructor(
    public val viewModel: ApplicationViewModel,
    public val newLineKey: StateFlow<NewLineKey>,
    public val sidebarSettings: SidebarSettingsViewModel,
)

/** Fault-injection checkpoints for isolated startup/shutdown validation. */
public enum class ApplicationStartupPhase { HomePrepared, BackendReady, ServicesRegistered, FrontendReady, RendererStarted }

/**
 * One structured CLI host. The supplied prepared Home is consumed and released last.
 * Factories are composition-root test seams, never dependencies of a frontend ViewModel.
 */
public suspend fun <R> withKodexApplication(
    homeHandle: KodexHomeHandle,
    workingDirectory: Path = Path("."),
    codexDirectory: Path = Path(requireUserHomeDirectory(), ".codex"),
    agentsDirectory: Path = Path(requireUserHomeDirectory(), ".agents"),
    applicationWidth: Int? = null,
    createClient: (OpenAiAuthStore) -> ModelClient = { OpenAiClient(it) },
    createLoginClient: () -> LoginClient = { OpenAiLoginClient() },
    onPhase: suspend (ApplicationStartupPhase) -> Unit = {},
    render: suspend CoroutineScope.(KodexApplication) -> R,
): R {
    var primary: Throwable? = null
    try {
        require(homeHandle.version == CurrentKodexApplicationVersion) { "The Home was not prepared for this application version." }
        onPhase(ApplicationStartupPhase.HomePrepared)
        val cwd = SystemCoroutineFileSystem.resolve(workingDirectory)
        return withBackendServices(
            homeHandle.home, resolveAllowingMissing(codexDirectory), resolveAllowingMissing(agentsDirectory),
            createClient = createClient, createLoginClient = createLoginClient,
        ) { backend ->
            onPhase(ApplicationStartupPhase.BackendReady)
            withInMemoryRpc(registerServices = { backend.register(this) }) { rawClient ->
                onPhase(ApplicationStartupPhase.ServicesRegistered)
                val owner = Job(coroutineContext[Job])
                val frontendScope = CoroutineScope(coroutineContext + owner)
                var application: ApplicationViewModel? = null
                var views: RpcSessionViews? = null
                var global: RpcGlobalSettings? = null
                var failure: Throwable? = null
                try {
                    val services = RpcServices(RestoringRpcClient(rawClient))
                    val frontendStore = openCliFrontendSettings(homeHandle.home)
                    val settings = RpcGlobalSettings.open(services.global, frontendStore, frontendScope, applicationWidth ?: 0)
                        .also { global = it }
                    lateinit var root: ApplicationViewModelImpl
                    val sessionViews = RpcSessionViews(frontendScope, services, settings.models) { created ->
                        frontendScope.launch {
                            try { root.openCreatedSessions(created.map { it.sessionIndex }) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Throwable) { ApplicationLogger.error(error) { "Unable to open created Session tabs." } }
                        }
                    }.also { views = it }
                    val sessions = DefaultPersistedSessionViewModelRegistry(sessionViews, settings.models, frontendScope)
                    val draftFactory = DefaultNewSessionViewModelFactory(sessionViews, sessions, settings.models, frontendScope)
                    val directoryPicker = { path: Path -> createDirectoryPickerViewModel(path, frontendScope) }
                    root = ApplicationViewModelImpl(
                        sessions, draftFactory,
                        SessionCatalogViewModelFactory { dependencies ->
                            DefaultSessionCatalogViewModel(frontendScope, dependencies)
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
                                createSessionSettingsViewModel(source, settings.models, frontendScope, directoryPicker),
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
                        try { collectNotificationHooks(services.global.getNotificationFlow(), frontendStore.settings, cwd) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Throwable) { ApplicationLogger.error(error) { "Notification subscription ended." } }
                    }
                    val result = KodexApplication(
                        root, frontendStore.settings.map { it.newLineKey }.stateIn(
                            frontendScope, SharingStarted.Eagerly, frontendStore.settings.value.newLineKey,
                        ),
                        SidebarSettingsViewModelImpl(settings, frontendScope, applicationWidth != null),
                    )
                    onPhase(ApplicationStartupPhase.FrontendReady)
                    onPhase(ApplicationStartupPhase.RendererStarted)
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
        }
    } catch (error: Throwable) {
        primary = error
        throw error
    } finally {
        try { withContext(NonCancellable) { homeHandle.closeAndJoin() } }
        catch (error: Throwable) { if (primary == null) throw error else primary.addSuppressed(error) }
    }
}

private suspend fun resolveAllowingMissing(path: Path): Path {
    if (SystemCoroutineFileSystem.metadataOrNull(path) != null) return SystemCoroutineFileSystem.resolve(path)
    val parent = path.parent
    return Path(if (parent == null) SystemCoroutineFileSystem.resolve(Path(".")) else resolveAllowingMissing(parent), path.name)
}
private val ApplicationLogger = KotlinLogging.logger {}
