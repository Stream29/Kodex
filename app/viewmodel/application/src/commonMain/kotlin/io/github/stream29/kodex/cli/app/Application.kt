package io.github.stream29.kodex.cli.app

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.agentsession.contract.KodexAgentDependencies
import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
import io.github.stream29.kodex.agentsession.filesystem.FileSystemKodexSessionRepository
import io.github.stream29.kodex.agentcontext.contract.AgentContextSettings
import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.app.agent.contract.ComposerViewModelFactory
import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModelArguments
import io.github.stream29.kodex.app.session.contract.NewSessionViewModelFactory
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModelRegistry
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModelFactory
import io.github.stream29.kodex.app.pathpicker.createDirectoryPickerViewModel
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import io.github.stream29.kodex.app.settings.DefaultOpenAiLoginViewModelFactory
import io.github.stream29.kodex.app.settings.DefaultSettingsViewModelFactory
import io.github.stream29.kodex.app.settings.SettingsViewModelDependencies
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginViewModelFactory
import io.github.stream29.kodex.app.settings.contract.SettingsViewModelFactory
import io.github.stream29.kodex.cli.agent.AgentAutomaticTitleConfiguration
import io.github.stream29.kodex.cli.agent.AgentAutomaticTitleSettings
import io.github.stream29.kodex.cli.agent.AgentRuntimeHistoryViewModelFactory
import io.github.stream29.kodex.cli.agent.AgentRuntimeViewModelArguments
import io.github.stream29.kodex.cli.agent.DefaultAgentRuntimeViewModelFactory
import io.github.stream29.kodex.cli.auth.FileSystemKodexAuthStore
import io.github.stream29.kodex.cli.auth.KodexAuthStore
import io.github.stream29.kodex.cli.history.DefaultAgentHistoryViewModelFactory
import io.github.stream29.kodex.cli.newsession.DEFAULT_NEW_SESSION_NAME
import io.github.stream29.kodex.cli.session.PersistedSessionAgentViewModelFactory
import io.github.stream29.kodex.cli.sessiontitle.OpenAiSessionTitleGenerator
import io.github.stream29.kodex.cli.sessiontitle.SessionTitleGenerator
import io.github.stream29.kodex.cli.settings.KodexGlobalSettings
import io.github.stream29.kodex.cli.settings.KodexGlobalSettingsStore
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.cli.settings.openGlobalSettings
import io.github.stream29.kodex.hook.contract.HookManager
import io.github.stream29.kodex.hook.impl.HookManagerImpl
import io.github.stream29.kodex.hook.impl.KodexHooksImpl
import io.github.stream29.kodex.mcp.contract.McpCodexImportSource
import io.github.stream29.kodex.mcp.contract.McpManager
import io.github.stream29.kodex.mcp.impl.DefaultMcpOAuthClient
import io.github.stream29.kodex.mcp.impl.McpManagerImpl
import io.github.stream29.kodex.mcp.impl.McpServiceImpl
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.Reasoning
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageStore
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageStore as createCodexAccountUsageStore
import io.github.stream29.kodex.openai.client.OpenAiClient
import io.github.stream29.kodex.openai.client.OpenAiClientConfig
import io.github.stream29.kodex.openai.client.contract.OpenAiClient as OpenAiClientContract
import io.github.stream29.kodex.openai.codexclistorage.CodexCliStorage
import io.github.stream29.kodex.openai.codexclistorage.CodexCliMcpImportCandidate
import io.github.stream29.kodex.openai.modelcatalog.OpenAiModelCatalog
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.kodexhome.KodexHome
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.logging.global
import io.github.stream29.kodex.utils.osenvironment.environmentVariable
import io.github.stream29.kodex.utils.osenvironment.requireUserHomeDirectory
import io.github.stream29.kodex.utils.shellclient.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.io.files.Path
import org.koin.core.KoinApplication
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam
import org.koin.core.parameter.parametersOf
import org.koin.plugin.module.dsl.koinApplication

/**
 * Process host and composition root.
 *
 * The frontend receives only [viewModel]. Infrastructure children remain
 * private and are injected into their exact ViewModel factories here.
 */
public class KodexApplication private constructor(
    private val dependencyGraph: KoinApplication,
    private val dependencies: KodexAgentDependencies,
    private val sessionRepository: FileSystemKodexSessionRepository,
    private val authStore: KodexAuthStore,
    private val accountUsageStore: CodexAccountUsageStore,
    private val mcpManager: McpManager,
    private val hookManager: HookManager,
    private val applicationScope: CoroutineScope,
    public val viewModel: ApplicationViewModel,
    public val newLineKey: StateFlow<NewLineKey>,
) : AutoCloseable {
    private var closed = false

    public suspend fun shutdown() {
        if (closed) return
        closed = true
        viewModel.shutdown()
        closeInfrastructure()
    }

    override fun close() {
        if (closed) return
        closed = true
        viewModel.close()
        closeInfrastructure()
    }

    private fun closeInfrastructure() {
        sessionRepository.cancel()
        accountUsageStore.close()
        mcpManager.close()
        hookManager.close()
        dependencies.close()
        authStore.close()
        applicationScope.cancel()
        dependencyGraph.close()
        ApplicationLogger.info { "Application closed." }
    }

    public companion object {
        public suspend fun openDefault(
            workingDirectory: Path = Path("."),
        ): KodexApplication = open(
            codexDirectory = configuredCodexSourceHome(),
            agentsDirectory = defaultAgentsHome(),
            workingDirectory = workingDirectory,
            dataDirectory = KodexHome,
        )

        public suspend fun open(
            codexDirectory: Path,
            agentsDirectory: Path = defaultAgentsHome(),
            workingDirectory: Path = Path("."),
            dataDirectory: Path = KodexHome,
            sessionTitleGeneratorFactory: (OpenAiClientContract) -> SessionTitleGenerator =
                ::OpenAiSessionTitleGenerator,
            sessionRepositoryFactory:
            suspend CoroutineScope.(Path, KodexAgentDependencies) ->
            FileSystemKodexSessionRepository = { root, dependencies ->
                FileSystemKodexSessionRepository(root, dependencies)
            },
        ): KodexApplication {
            val scope = CoroutineScope(currentCoroutineContext()).supervisorChildScope()
            val resolvedWorkingDirectory = SystemCoroutineFileSystem.resolve(workingDirectory)
            val resolvedAgentsDirectory = SystemCoroutineFileSystem.resolve(agentsDirectory)
            val globalSettings = openGlobalSettings(
                settingsDirectory = dataDirectory,
                defaults = KodexGlobalSettings(codexHome = codexDirectory),
            )
            val authStore = scope.FileSystemKodexAuthStore(
                dataDirectory = dataDirectory,
                globalSettings = globalSettings,
            )
            val clientConfig = OpenAiClientConfig()
            val contextSettings = globalSettings.settings
                .map { settings ->
                    ApplicationAgentContextSettings(
                        agentsHome = resolvedAgentsDirectory,
                        shell = settings.shell,
                    )
                }
                .stateIn(
                    scope = scope,
                    started = SharingStarted.Eagerly,
                    initialValue = ApplicationAgentContextSettings(
                        agentsHome = resolvedAgentsDirectory,
                        shell = globalSettings.settings.value.shell,
                    ),
                )
            val mcpConfigurationStore = KodexMcpConfigurationStore(globalSettings, scope)
            val mcpOAuth = scope.DefaultMcpOAuthClient()
            val mcpService = scope.McpServiceImpl(
                settings = globalSettings.settings,
                configurationStore = mcpConfigurationStore,
                tokenRefresher = mcpOAuth,
            )
            val mcpManager = scope.McpManagerImpl(
                store = mcpConfigurationStore,
                service = mcpService,
                codexImportSource = McpCodexImportSource {
                    CodexCliStorage(globalSettings.settings.value.codexHome)
                        .readMcpImportCandidates()
                        .map(CodexCliMcpImportCandidate::toKodexMcpImportCandidate)
                },
                loginAttemptFactory = mcpOAuth,
            )
            val hookManager = scope.HookManagerImpl(
                store = KodexHookConfigurationStore(globalSettings, scope),
            )
            val graph = koinApplication<KodexKoinApplication>()
            try {
                val openAiServices = graph.koin.get<OpenAiApplicationServices> {
                    parametersOf(
                        authStore,
                        clientConfig,
                    )
                }
                val client = openAiServices.client
                val modelCatalog = openAiServices.modelCatalog
                val accountUsage = openAiServices.accountUsage
                val hooks = scope.KodexHooksImpl(globalSettings.settings)
                val dependencies = KodexAgentDependencies(
                    client = client,
                    modelCatalog = modelCatalog,
                    contextSettings = contextSettings,
                    shellSettings = globalSettings.settings,
                    mcpService = mcpService,
                    hooks = hooks,
                )
                val repository = scope.sessionRepositoryFactory(dataDirectory, dependencies)
                val automaticTitles = AgentAutomaticTitleConfiguration(
                    generator = sessionTitleGeneratorFactory(client),
                    settingsProvider = {
                        globalSettings.settings.value.sessionTitle.let { settings ->
                            AgentAutomaticTitleSettings(
                                enabled = settings.enabled,
                                model = settings.model,
                                reasoningEffort = settings.reasoningEffort,
                            )
                        }
                    },
                )
                val agentHistoryFactory = AgentRuntimeHistoryViewModelFactory {
                        agentSession,
                        ownerScope,
                    ->
                    val agentState: KodexAgentState = agentSession.runtime
                    graph.koin.get<DefaultAgentHistoryViewModelFactory> {
                        parametersOf(agentState, ownerScope)
                    }.create()
                }
                val sessionAgentFactory = PersistedSessionAgentViewModelFactory {
                        agentSession,
                        address,
                        parentAddress,
                        ownerScope,
                        isRoot,
                    ->
                    graph.koin.get<DefaultAgentRuntimeViewModelFactory> {
                        parametersOf(
                            AgentRuntimeViewModelArguments(
                                session = agentSession,
                                address = address,
                                parentAddress = parentAddress,
                                ownerScope = ownerScope,
                                models = modelCatalog.models,
                                automaticTitleConfiguration = automaticTitles.takeIf { isRoot },
                            ),
                            agentHistoryFactory,
                        )
                    }.create()
                }
                val store = graph.koin.get<PersistedSessionViewModelRegistry> {
                    parametersOf(repository as KodexSessionRepository, scope, sessionAgentFactory)
                }
                val catalogFactory = graph.koin.get<SessionCatalogViewModelFactory> {
                    parametersOf(repository as KodexSessionRepository, scope)
                }
                val composerFactory = graph.koin.get<ComposerViewModelFactory>()
                val newSessionFactory = graph.koin.get<NewSessionViewModelFactory> {
                    parametersOf(store, composerFactory, modelCatalog.models)
                }
                val settingsFactory = SettingsViewModelFactory { arguments ->
                    val pickerFactory = { initialDirectory: Path ->
                        createDirectoryPickerViewModel(initialDirectory, scope)
                    }
                    graph.koin.get<DefaultSettingsViewModelFactory> {
                        parametersOf(
                            SettingsViewModelDependencies(
                                initialPage = arguments.initialPage,
                                globalSettings = globalSettings,
                                authentication = authStore,
                                accountUsage = accountUsage,
                                mcpManager = mcpManager,
                                hookManager = hookManager,
                                models = modelCatalog.models,
                                sessionSettings = ContractSessionSettingsDataSource(
                                    target = arguments.target,
                                    scope = scope.supervisorChildScope(),
                                ),
                                createDirectoryPicker = pickerFactory,
                                ownerScope = scope,
                            ),
                        )
                    }.create()
                }
                val loginFactory = OpenAiLoginViewModelFactory {
                    graph.koin.get<DefaultOpenAiLoginViewModelFactory> {
                        parametersOf(authStore, scope)
                    }.create()
                }
                val applicationViewModel = graph.koin.get<ApplicationViewModel> {
                    parametersOf(
                        store,
                        newSessionFactory,
                        catalogFactory,
                        settingsFactory,
                        loginFactory,
                        { initialDirectory: Path ->
                            createDirectoryPickerViewModel(initialDirectory, scope)
                        },
                        NewSessionViewModelArgumentsProvider(
                            globalSettings = globalSettings,
                            workingDirectory = resolvedWorkingDirectory,
                        ),
                    )
                }
                val newLineKey = globalSettings.settings
                    .map { settings -> settings.newLineKey }
                    .stateIn(
                        scope = scope,
                        started = SharingStarted.Eagerly,
                        initialValue = globalSettings.settings.value.newLineKey,
                    )
                return KodexApplication(
                    dependencyGraph = graph,
                    dependencies = dependencies,
                    sessionRepository = repository,
                    authStore = authStore,
                    accountUsageStore = accountUsage,
                    mcpManager = mcpManager,
                    hookManager = hookManager,
                    applicationScope = scope,
                    viewModel = applicationViewModel,
                    newLineKey = newLineKey,
                ).also {
                    ApplicationLogger.info { "Application opened." }
                }
            } catch (failure: Throwable) {
                hookManager.close()
                mcpManager.close()
                mcpService.close()
                scope.cancel()
                graph.close()
                throw failure
            }
        }
    }
}

/**
 * OpenAI services whose constructors require process-specific runtime values.
 *
 * Koin owns this typed definition while [KodexApplication] supplies values
 * that cannot be known by a static module.
 */
@Factory
internal class OpenAiApplicationServices(
    @InjectedParam authStore: KodexAuthStore,
    @InjectedParam clientConfig: OpenAiClientConfig,
) {
    val client: OpenAiClient = OpenAiClient(authStore, clientConfig)
    val accountUsage: CodexAccountUsageStore =
        createCodexAccountUsageStore(client, authStore)
    val modelCatalog: OpenAiModelCatalog = OpenAiModelCatalog(client)
}

/** Exact runtime arguments for one compiler-generated application definition. */
internal class NewSessionViewModelArgumentsProvider(
    private val globalSettings: KodexGlobalSettingsStore,
    private val workingDirectory: Path,
) {
    fun create(ordinal: Int): NewSessionViewModelArguments =
        NewSessionViewModelArguments(
            defaultName = if (ordinal == 1) {
                DEFAULT_NEW_SESSION_NAME
            } else {
                "$DEFAULT_NEW_SESSION_NAME $ordinal"
            },
            initialSettings = globalSettings.settings.value.newSession
                .toAgentSettings(workingDirectory),
        )
}

@Factory(binds = [ApplicationViewModel::class])
internal fun createApplicationViewModel(
    @InjectedParam sessions: PersistedSessionViewModelRegistry,
    @InjectedParam newSessionFactory: NewSessionViewModelFactory,
    @InjectedParam catalogFactory: SessionCatalogViewModelFactory,
    @InjectedParam settingsFactory: SettingsViewModelFactory,
    @InjectedParam loginFactory: OpenAiLoginViewModelFactory,
    @InjectedParam createDirectoryPicker: (Path) -> DirectoryPickerViewModel,
    @InjectedParam newSessionArguments: NewSessionViewModelArgumentsProvider,
): ApplicationViewModel = ApplicationViewModelImpl(
    sessions = sessions,
    newSessionFactory = newSessionFactory,
    catalogFactory = catalogFactory,
    settingsFactory = settingsFactory,
    loginFactory = loginFactory,
    createDirectoryPicker = createDirectoryPicker,
    newSessionArguments = newSessionArguments::create,
)

private fun io.github.stream29.kodex.cli.settings.KodexNewSessionSettings.toAgentSettings(
    workingDirectory: Path,
): KodexAgentSettings = KodexAgentSettings(
    model = model,
    cwd = workingDirectory,
    agentMode = agentMode,
    requestUserInputMode = requestUserInputMode,
    reasoning = Reasoning(effort = reasoningEffort),
    serviceTier = serviceTier,
)

private data class ApplicationAgentContextSettings(
    override val agentsHome: Path,
    override val shell: Shell,
) : AgentContextSettings

private fun configuredCodexSourceHome(): Path =
    environmentVariable("CODEX_HOME")
        ?.takeIf(String::isNotBlank)
        ?.let(::Path)
        ?: Path(requireUserHomeDirectory(), ".codex")

private fun defaultAgentsHome(): Path = Path(requireUserHomeDirectory(), ".agents")

private val ApplicationLogger: KLogger by lazy {
    KotlinLogging.logger {}.global()
}
