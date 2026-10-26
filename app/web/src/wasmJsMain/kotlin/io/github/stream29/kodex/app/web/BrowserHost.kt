package io.github.stream29.kodex.app.web

import io.github.stream29.kodex.app.contextsourcesettings.ContextSourcePathPolicy
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import io.github.stream29.kodex.cli.app.FrontendHost
import io.github.stream29.kodex.cli.rpc.RpcFrontendEnvironment
import io.github.stream29.kodex.cli.rpc.RpcOAuthCallbackProvider
import io.github.stream29.kodex.rpc.models.NotificationHook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.io.files.Path

internal class BrowserHost(private val cwd: Path, private val diagnostic: (String?) -> Unit) : FrontendHost {
    val rpcEnvironment = object : RpcFrontendEnvironment {
        override val contextPaths = object : ContextSourcePathPolicy {
            override val builtInNormalizedPaths: Set<String> = emptySet()
            override fun normalize(path: String): String? = error("Context path editing is unavailable in this browser host.")
        }
        override val oauthCallbacks = RpcOAuthCallbackProvider { _, _, _ ->
            error("Provider OAuth is unavailable in this browser host. Use the Native client.")
        }
    }
    override suspend fun resolveWorkingDirectory(directory: Path): Path {
        require(directory == cwd) { "This browser connection is bound to its backend-confirmed initial directory." }
        return cwd
    }
    override fun createDirectoryPicker(directory: Path, owner: CoroutineScope): DirectoryPickerViewModel =
        error("Directory browsing is unavailable in this browser host.")
    override suspend fun collectFrontendErrors(
        hooks: () -> List<NotificationHook>, workingDirectory: Path, errors: Flow<String?>,
    ) { errors.collect(diagnostic) }
}
