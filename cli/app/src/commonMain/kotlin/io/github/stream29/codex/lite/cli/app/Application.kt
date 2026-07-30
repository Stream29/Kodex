package io.github.stream29.codex.lite.cli.app

import io.github.stream29.codex.lite.agentsession.contract.CodexAgentDependencies
import io.github.stream29.codex.lite.agentsession.filesystem.FileSystemCodexSessionRepository
import io.github.stream29.codex.lite.cli.auth.CodexAuthStore
import io.github.stream29.codex.lite.cli.auth.FileSystemCodexAuthStore
import io.github.stream29.codex.lite.cli.agent.AgentAutomaticTitleConfiguration
import io.github.stream29.codex.lite.cli.agent.AgentAutomaticTitleSettings
import io.github.stream29.codex.lite.cli.session.SessionRepositoryViewModel
import io.github.stream29.codex.lite.cli.session.SessionRepositoryViewModel as createSessionRepositoryViewModel
import io.github.stream29.codex.lite.cli.settings.CodexGlobalSettings
import io.github.stream29.codex.lite.cli.settings.CodexGlobalSettingsStore
import io.github.stream29.codex.lite.cli.settings.openGlobalSettings
import io.github.stream29.codex.lite.hook.impl.CodexHooksImpl
import io.github.stream29.codex.lite.mcp.contract.McpService
import io.github.stream29.codex.lite.mcp.impl.McpServiceImpl
import io.github.stream29.codex.lite.openai.client.OpenAiClient
import io.github.stream29.codex.lite.openai.client.OpenAiClientConfig
import io.github.stream29.codex.lite.openai.client.contract.OpenAiClient as OpenAiClientContract
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliStorage
import io.github.stream29.codex.lite.openai.modelcatalog.OpenAiModelCatalog
import io.github.stream29.codex.lite.cli.sessiontitle.OpenAiSessionTitleGenerator
import io.github.stream29.codex.lite.cli.sessiontitle.SessionTitleGenerator
import io.github.stream29.codex.lite.utils.codexlitehome.CodexLiteHome
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.codex.lite.utils.osenvironment.requireUserHomeDirectory
import io.github.stream29.codex.lite.utils.osenvironment.environmentVariable
import io.github.stream29.codex.lite.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.io.files.Path
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module

internal class CodexLiteApplication private constructor(
    private val dependencyGraph: KoinApplication,
    private val dependencies: CodexAgentDependencies,
    private val sessionRepository: FileSystemCodexSessionRepository,
    private val authStore: CodexAuthStore,
    private val applicationScope: CoroutineScope,
    internal val sessionViewModel: SessionTreeCliViewModel,
    internal val globalSettings: CodexGlobalSettingsStore,
) : AutoCloseable {
    private var closed = false

    /** Releases application-owned resources. */
    internal suspend fun shutdown() {
        if (closed) return
        closed = true
        sessionViewModel.close()
        sessionRepository.cancel()
        dependencies.close()
        authStore.close()
        applicationScope.cancel()
        dependencyGraph.close()
    }

    override fun close() {
        if (closed) return
        closed = true
        sessionViewModel.close()
        sessionRepository.cancel()
        dependencies.close()
        authStore.close()
        applicationScope.cancel()
        dependencyGraph.close()
    }

    internal companion object {
        suspend fun openDefault(
            workingDirectory: Path = Path("."),
        ): CodexLiteApplication =
            open(
                codexDirectory = configuredCodexHome(),
                workingDirectory = workingDirectory,
                dataDirectory = CodexLiteHome,
            )

        suspend fun open(
            codexDirectory: Path,
            workingDirectory: Path = Path("."),
            dataDirectory: Path = CodexLiteHome,
            sessionTitleGeneratorFactory: (OpenAiClientContract) -> SessionTitleGenerator =
                ::OpenAiSessionTitleGenerator,
            sessionRepositoryFactory:
                suspend CoroutineScope.(Path, CodexAgentDependencies) -> FileSystemCodexSessionRepository =
                { root, dependencies ->
                    FileSystemCodexSessionRepository(root, dependencies)
                },
        ): CodexLiteApplication {
            val applicationScope = CoroutineScope(currentCoroutineContext()).supervisorChildScope()
            val resolvedWorkingDirectory = SystemCoroutineFileSystem.resolve(workingDirectory)
            val initialCodexCliStorage = CodexCliStorage(codexDirectory)
            val globalSettings = initialCodexCliStorage.openGlobalSettings(
                settingsDirectory = dataDirectory,
                workingDirectory = resolvedWorkingDirectory,
                defaults = CodexGlobalSettings(codexHome = codexDirectory),
            )
            val selectedCodexDirectory = globalSettings.settings.value.codexHome
            val codexCliStorage = CodexCliStorage(selectedCodexDirectory)
            val authStore = try {
                applicationScope.FileSystemCodexAuthStore(
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
                            single<CodexAuthStore> { authStore }
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
                applicationScope.CodexHooksImpl(globalSettings.settings)
            } catch (failure: Throwable) {
                modelCatalog.close()
                client.close()
                mcpService.close()
                applicationScope.cancel()
                koin.close()
                throw failure
            }
            val dependencies = CodexAgentDependencies(
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
            return CodexLiteApplication(
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
