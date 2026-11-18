@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.app

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.app.application.contract.*
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerDependencies
import io.github.stream29.kodex.app.pathpicker.createDirectoryPickerViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModelArguments
import io.github.stream29.kodex.app.sessioncatalog.DefaultSessionCatalogViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModelFactory
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.app.settings.createOpenAiLoginViewModel
import io.github.stream29.kodex.app.settings.createSessionSettingsViewModel
import io.github.stream29.kodex.app.settings.createSettingsViewModel
import io.github.stream29.kodex.cli.newsession.DefaultNewSessionViewModelFactory
import io.github.stream29.kodex.cli.rpc.*
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.local.FrontendRpcConnection
import io.github.stream29.kodex.rpc.models.FrontendErrorAdmission
import io.github.stream29.kodex.rpc.models.OAuthTarget
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path

public actual suspend fun <R> withFrontendApplication(
    connection: FrontendRpcConnection,
    workingDirectory: Path,
    applicationWidth: Int?,
    render: suspend CoroutineScope.(KodexApplication) -> R,
): R = coroutineScope {
    val cwd = workingDirectory
    val owner = Job(coroutineContext[Job])
    val frontendScope = CoroutineScope(coroutineContext + owner)
    var application: ApplicationViewModel? = null
    var views: RpcSessionViews? = null
    var global: RpcGlobalSettings? = null
    var failure: Throwable? = null
    try {
        val services = RpcServices(RestoringRpcClient(connection))
        val oauthRpc = { RpcServices(RestoringRpcClient(connection.bindOperation())).global }
        val pickerDependencies = object : DirectoryPickerDependencies {
            override val browser = RpcDirectoryPickerBrowser(services.global, cwd.toString())
        }
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
        val settings = RpcGlobalSettings.open(services.global, frontendScope, applicationWidth ?: 0)
            .also { global = it }
        lateinit var root: ApplicationViewModel
        val sessionViews = RpcSessionViews(
            frontendScope, services, settings.models, reportUnhandledError,
            connectionAvailable = { connection.available },
        ).also { views = it }
        connection.onReconnection { client, isConnected ->
            sessionViews.reactivate(RpcServices(RestoringRpcClient(client)), isConnected)
        }
        val draftFactory = DefaultNewSessionViewModelFactory(sessionViews, sessionViews, settings, frontendScope)
        val directoryPicker = { path: Path ->
            createDirectoryPickerViewModel(path, pickerDependencies, frontendScope)
        }
        root = createApplicationViewModel(
            sessionViews, draftFactory,
            SessionCatalogViewModelFactory { dependencies, interactions ->
                DefaultSessionCatalogViewModel(frontendScope, dependencies, interactions)
            },
            catalogDependencies = RpcSessionCatalogDependencies(services.global),
            SettingsViewModelFactory { arguments ->
                val source = when (val target = arguments.target) {
                    is RpcSessionView -> RpcSessionSettingsSource(target, frontendScope)
                    null -> UnavailableSessionSettingsDataSource
                    else -> error("Unknown frontend Session view.")
                }
                createSettingsViewModel(
                    arguments.initialPage,
                    RpcGlobalEditor(settings, services.global, frontendScope, cwd.toString(), oauthRpc),
                    createSessionSettingsViewModel(
                        SessionSettingsDependencies(source, settings.models, directoryPicker),
                        frontendScope,
                    ),
                    RpcNewSessionSettings(settings, frontendScope),
                )
            },
            OpenAiLoginViewModelFactory {
                val target = OAuthTarget.OpenAi(settings.settings.value.authSource)
                createOpenAiLoginViewModel(
                    dependencies = OpenAiLoginDependencies {
                        startRpcOAuth(oauthRpc(), target, frontendScope)
                    },
                    ownerScope = frontendScope,
                )
            },
            directoryPicker,
            NewSessionViewModelArguments(cwd),
            CoroutineScope(frontendScope.coroutineContext + CoroutineExceptionHandler { _, error ->
                reportUnhandledError(error)
            }),
        )
        application = root
        frontendScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                unhandledErrors.collect { message ->
                    try {
                        if (services.global.reportFrontendError(message) == FrontendErrorAdmission.RejectedOverload) {
                            ApplicationLogger.warn { "Frontend error observation was rejected by the backend queue." }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        ApplicationLogger.warn { "Frontend error observation could not be admitted." }
                    }
                }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { ApplicationLogger.error(error) { "Notification subscription ended." } }
        }
        val result = KodexApplication(
            root,
            settings.frontend.settings.projectState { it.newLineKey },
            createSidebarSettingsViewModel(settings, frontendScope, applicationWidth != null),
            reportUnhandledError,
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
