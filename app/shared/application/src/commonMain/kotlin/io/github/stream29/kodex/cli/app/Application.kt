package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.agentsession.contract.KodexAgentDependencies
import io.github.stream29.kodex.agentsession.filesystem.FileSystemKodexSessionRepository
import io.github.stream29.kodex.cli.auth.KodexAuthStore
import io.github.stream29.kodex.cli.auth.FileSystemKodexAuthStore
import io.github.stream29.kodex.cli.agent.AgentAutomaticTitleConfiguration
import io.github.stream29.kodex.cli.agent.AgentAutomaticTitleSettings
import io.github.stream29.kodex.cli.session.SessionRepositoryViewModel
import io.github.stream29.kodex.cli.session.SessionRepositoryViewModel as createSessionRepositoryViewModel
import io.github.stream29.kodex.cli.settings.KodexGlobalSettings
import io.github.stream29.kodex.cli.settings.KodexGlobalSettingsStore
import io.github.stream29.kodex.cli.settings.openGlobalSettings
import io.github.stream29.kodex.hook.impl.KodexHooksImpl
import io.github.stream29.kodex.mcp.contract.McpService
import io.github.stream29.kodex.mcp.impl.McpServiceImpl
import io.github.stream29.kodex.openai.client.OpenAiClient
import io.github.stream29.kodex.openai.client.OpenAiClientConfig
import io.github.stream29.kodex.openai.client.contract.OpenAiClient as OpenAiClientContract
import io.github.stream29.kodex.openai.codexclistorage.CodexCliStorage
import io.github.stream29.kodex.openai.modelcatalog.OpenAiModelCatalog
import io.github.stream29.kodex.cli.sessiontitle.OpenAiSessionTitleGenerator
import io.github.stream29.kodex.cli.sessiontitle.SessionTitleGenerator
import io.github.stream29.kodex.utils.kodexhome.KodexHome
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.osenvironment.requireUserHomeDirectory
import io.github.stream29.kodex.utils.osenvironment.environmentVariable
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.io.files.Path
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module

public class KodexApplication private constructor(
    private val dependencyGraph: KoinApplication,
    private val dependencies: KodexAgentDependencies,
    private val sessionRepository: FileSystemKodexSessionRepository,
    private val authStore: KodexAuthStore,
    private val applicationScope: CoroutineScope,
    public val sessionViewModel: SessionTreeCliViewModel,
    internal val globalSettings: KodexGlobalSettingsStore,
) : AutoCloseable {
    private var closed = false

    /** Releases application-owned resources. */
    public suspend fun shutdown() {
        if (closed) return
        closed = true
        sessionViewModel.close()
        sessionRepository.cancel()
        dependencies.close()
        authStore.close()
        applicationScope.cancel()
        dependencyGraph.close()
    }

    public override fun close() {
        if (closed) return
        closed = true
        sessionViewModel.close()
        sessionRepository.cancel()
        dependencies.close()
        authStore.close()
        applicationScope.cancel()
        dependencyGraph.close()
    }

    public companion object {
        public suspend fun openDefault(
            workingDirectory: Path = Path("."),
        ): KodexApplication =
            open(
                codexDirectory = configuredCodexHome(),
                workingDirectory = workingDirectory,
                dataDirectory = KodexHome,
            )

        public suspend fun open(
            codexDirectory: Path,
            workingDirectory: Path = Path("."),
            dataDirectory: Path = KodexHome,
            sessionTitleGeneratorFactory: (OpenAiClientContract) -> SessionTitleGenerator =
                ::OpenAiSessionTitleGenerator,
            sessionRepositoryFactory:
                suspend CoroutineScope.(Path, KodexAgentDependencies) -> FileSystemKodexSessionRepository =
                { root, dependencies ->
                    FileSystemKodexSessionRepository(root, dependencies)
                },
        ): KodexApplication {
            val applicationScope = CoroutineScope(currentCoroutineContext()).supervisorChildScope()
            val resolvedWorkingDirectory = SystemCoroutineFileSystem.resolve(workingDirectory)
            val initialCodexCliStorage = CodexCliStorage(codexDirectory)
            val globalSettings = initialCodexCliStorage.openGlobalSettings(
                settingsDirectory = dataDirectory,
                workingDirectory = resolvedWorkingDirectory,
                defaults = KodexGlobalSettings(codexHome = codexDirectory),
            )
            val selectedCodexDirectory = globalSettings.settings.value.codexHome
            val codexCliStorage = CodexCliStorage(selectedCodexDirectory)
            val authStore = try {
                applicationScope.FileSystemKodexAuthStore(
                    dataDirectory = dataDirectory,
                    globalSettings = globalSettings,
                )
            } catch (failure: Throwable) {
                applicationScope.cancel()
                throw failure
            }
            val clientConfig = codexCliStorage.readModelsCacheOrNull()
                ?.clientVersion
                ?.let { clientVersion -> OpenAiClientConfig(clientVersion = clientVersion) }
                ?: OpenAiClientConfig()
            val mcpService = try {
                applicationScope.McpServiceImpl(
                    settings = globalSettings.settings,
                )
            } catch (failure: Throwable) {
                applicationScope.cancel()
                throw failure
            }
            val koin = try {
                koinApplication {
                    modules(
                        module {
                            single { codexCliStorage }
                            single { globalSettings }
                            single<KodexAuthStore> { authStore }
                            single { OpenAiClient(get(), clientConfig) }
                            single<OpenAiClientContract> { get<OpenAiClient>() }
                            single { OpenAiModelCatalog(get(), get()) }
                            single<McpService> { mcpService }
                        },
                    )
                }
            } catch (failure: Throwable) {
                mcpService.close()
                applicationScope.cancel()
                throw failure
            }
            val client: OpenAiClient = try {
                koin.koin.get()
            } catch (failure: Throwable) {
                mcpService.close()
                applicationScope.cancel()
                koin.close()
                throw failure
            }
            val modelCatalog: OpenAiModelCatalog = try {
                koin.koin.get()
            } catch (failure: Throwable) {
                client.close()
                mcpService.close()
                applicationScope.cancel()
                koin.close()
                throw failure
            }
            val hooks = try {
                applicationScope.KodexHooksImpl(globalSettings.settings)
            } catch (failure: Throwable) {
                modelCatalog.close()
                client.close()
                mcpService.close()
                applicationScope.cancel()
                koin.close()
                throw failure
            }
            val dependencies = KodexAgentDependencies(
                client = client,
                modelCatalog = modelCatalog,
                contextSettings = globalSettings.settings,
                shellSettings = globalSettings.settings,
                mcpService = mcpService,
                hooks = hooks,
            )
            val sessionRepository = try {
                applicationScope.sessionRepositoryFactory(dataDirectory, dependencies)
            } catch (failure: Throwable) {
                try {
                    dependencies.close()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                applicationScope.cancel()
                koin.close()
                throw failure
            }
            val sessionViewModel = try {
                val sessionTitleGenerator = sessionTitleGeneratorFactory(client)
                val automaticTitleConfiguration = AgentAutomaticTitleConfiguration(
                    generator = sessionTitleGenerator,
                    settingsProvider = {
                        val settings = globalSettings.settings.value.sessionTitle
                        AgentAutomaticTitleSettings(
                            enabled = settings.enabled,
                            model = settings.model,
                            reasoningEffort = settings.reasoningEffort,
                        )
                    },
                )
                val repositoryViewModel = applicationScope.createSessionRepositoryViewModel(
                    repository = sessionRepository,
                    automaticTitleConfiguration = automaticTitleConfiguration,
                )
                SessionTreeCliViewModel(
                    repository = repositoryViewModel,
                    globalSettings = globalSettings,
                    workingDirectory = resolvedWorkingDirectory,
                    modelCatalog = dependencies.modelCatalog,
                    authStore = authStore,
                    parentScope = applicationScope,
                ).also { it.initialize() }
            } catch (failure: Throwable) {
                sessionRepository.cancel()
                try {
                    dependencies.close()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                applicationScope.cancel()
                koin.close()
                throw failure
            }
            return KodexApplication(
                dependencyGraph = koin,
                dependencies = dependencies,
                sessionRepository = sessionRepository,
                authStore = authStore,
                applicationScope = applicationScope,
                sessionViewModel = sessionViewModel,
                globalSettings = globalSettings,
            )
        }
    }
}

private fun configuredCodexHome(): Path =
    environmentVariable("CODEX_HOME")
        ?.takeIf(String::isNotBlank)
        ?.let(::Path)
        ?: Path(requireUserHomeDirectory(), ".codex")
