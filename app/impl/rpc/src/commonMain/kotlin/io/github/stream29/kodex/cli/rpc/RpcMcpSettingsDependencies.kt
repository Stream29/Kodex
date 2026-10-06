package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.app.mcpsettings.*
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsState
import io.github.stream29.kodex.mcp.contract.*
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.models.OAuthTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** Credentials and full settings baselines are private to this RPC adapter, not renderer state. */
internal class RpcMcpSettingsDependencies(
    private val global: RpcGlobalSettings,
    private val rpc: GlobalRpc,
    private val mcp: RpcMcpSettings,
    private val accept: (suspend () -> Unit) -> Boolean,
) : McpSettingsDependencies {
    override val servers: StateFlow<List<McpServerSettingsState>> = mcp.servers
    override val operationFailure: StateFlow<Boolean> = global.operationFailure

    override fun captureEditor(serverName: String?): McpEditHandle? {
        val captured = if (serverName == null) emptyMap() else mcp.capture()
        val initial = serverName?.let { captured[it]?.editorDraft(it) ?: return null }
        return object : McpEditHandle {
            override val originalName: String? = serverName
            override val initialDraft: McpServerDraft? = initial
            private var released = false
            override fun save(draft: McpServerDraft): McpWriteAdmission {
                if (released) return McpWriteAdmission.Rejected("The MCP editor is no longer active.")
                val admitted = accept {
                    check(mcp.save(captured, serverName, draft)) { "The MCP configuration changed." }
                }
                if (admitted) released = true
                return admission(admitted)
            }
            override fun release() { released = true }
        }
    }

    override fun captureServer(serverName: String): McpServerHandle? {
        val captured = mcp.capture()[serverName] ?: return null
        return object : McpServerHandle {
            override val serverName: String = serverName
            private var released = false
            override fun delete(): McpWriteAdmission {
                if (released) return McpWriteAdmission.Rejected("The MCP target is no longer active.")
                val admitted = accept {
                    check(mcp.delete(serverName, captured)) { "The MCP configuration changed." }
                }
                if (admitted) released = true
                return admission(admitted)
            }
            override fun setEnabled(enabled: Boolean): McpWriteAdmission {
                if (released) return McpWriteAdmission.Rejected("The MCP target is no longer active.")
                val admitted = accept {
                    check(mcp.setEnabled(serverName, captured, enabled)) { "The MCP configuration changed." }
                }
                if (admitted) released = true
                return admission(admitted)
            }
            override fun release() { released = true }
        }
    }

    override suspend fun readImport(): McpImportHandle = mcp.readImport(accept)
    override suspend fun startLogin(serverName: String, interactionScope: CoroutineScope): McpSettingsLogin? {
        val configuration = global.settings.value.mcpServers[serverName] as? McpServerConfiguration.StreamableHttp
            ?: return null
        val redirect = configuration.oauth?.client?.redirectUri ?: return null
        val attempt = startRpcOAuth(rpc, OAuthTarget.Mcp(serverName), interactionScope, redirect)
        return object : McpSettingsLogin {
            override val serverName: String = serverName
            override val authorizationUrl: String = attempt.authorizationUrl
            override suspend fun awaitCompletion() { attempt.awaitCompletion() }
            override fun cancel() { attempt.cancel() }
        }
    }
    override suspend fun reconnect(serverName: String) { mcp.reconnect(serverName) }
    override suspend fun logout(serverName: String) { mcp.logout(serverName) }
    override fun reportFailure(failure: Throwable) { global.reportOperationFailure(failure) }
    override fun dismissFailure() { global.dismissOperationFailure() }
}

private fun admission(accepted: Boolean): McpWriteAdmission =
    if (accepted) McpWriteAdmission.Accepted
    else McpWriteAdmission.Rejected("Settings is no longer accepting edits.")

private fun McpServerConfiguration.editorDraft(name: String): McpServerDraft = when (this) {
    is McpServerConfiguration.StreamableHttp -> McpServerDraft.StreamableHttp(
        name, enabled, McpStreamableHttpDraft(url, headers.mapValues { McpSecretDraft.Keep }, oauth?.let {
            McpOAuthDraft(it.client.clientId, it.client.clientSecret?.let { McpSecretDraft.Keep },
                it.client.redirectUri, it.client.authorizationEndpoint, it.client.tokenEndpoint, it.resource, it.scopes)
        }),
    )
    is McpServerConfiguration.Stdio -> McpServerDraft.Stdio(
        name, enabled, McpStdioDraft(command, args, environment.mapValues { McpSecretDraft.Keep }, workingDirectory),
    )
}
