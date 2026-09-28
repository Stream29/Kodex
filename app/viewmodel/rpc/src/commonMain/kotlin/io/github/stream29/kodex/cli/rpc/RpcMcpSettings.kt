package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.app.settings.toSettingsState
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsState
import io.github.stream29.kodex.mcp.contract.McpCodexImportCandidate
import io.github.stream29.kodex.mcp.contract.McpImportDecision
import io.github.stream29.kodex.mcp.contract.McpImportItem
import io.github.stream29.kodex.mcp.contract.McpImportItemKind
import io.github.stream29.kodex.mcp.contract.McpImportPreview
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.contract.McpServerDraft
import io.github.stream29.kodex.mcp.contract.toConfiguration
import io.github.stream29.kodex.mcp.contract.validatedName
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** A local editor keeps full configuration only in memory; list/detail rendering is sanitized. */
public class RpcMcpSettings(
    private val global: RpcGlobalSettings,
    private val rpc: GlobalRpc,
    scope: CoroutineScope,
) : AutoCloseable {
    private val owner = Job(scope.coroutineContext[Job])
    private val local = CoroutineScope(scope.coroutineContext + owner)
    public val servers: StateFlow<List<McpServerSettingsState>> = global.mcp
        .map { values -> values.map { it.toSettingsState() } }
        .stateIn(local, SharingStarted.Eagerly, global.mcp.value.map { it.toSettingsState() })
    private var nextPreview = 0L
    private var preview: Import? = null

    /** Capture before opening a delayed dialog, not when its submit coroutine finally runs. */
    public fun capture(): Map<String, McpServerConfiguration> {
        owner.ensureActive()
        global.ensureActive()
        return global.settings.value.mcpServers
    }

    public suspend fun save(
        captured: Map<String, McpServerConfiguration>,
        originalName: String?,
        draft: McpServerDraft,
    ): Boolean {
        owner.ensureActive()
        val name = draft.validatedName()
        require(originalName == name || captured[name] == null) { "An MCP server already has this name." }
        val previous = originalName?.let { captured[it] }
        if (originalName != null) requireNotNull(previous)
        val configuration = draft.toConfiguration(previous, preserveOAuth = originalName == name)
        val targets = setOfNotNull(originalName, name)
        return global.settings.editField(
            targets.associateWith { captured[it] },
            { value -> targets.associateWith { value.mcpServers[it] } },
            { value -> value.copy(mcpServers = value.mcpServers.toMutableMap().apply {
                originalName?.let(::remove)
                put(name, configuration)
            }) },
            { owner.ensureActive(); global.ensureActive() },
        )
    }

    public suspend fun delete(name: String, captured: McpServerConfiguration): Boolean =
        global.settings.editField(captured, { it.mcpServers[name] }, { it.copy(mcpServers = it.mcpServers - name) }) {
            owner.ensureActive(); global.ensureActive()
        }

    public suspend fun setEnabled(name: String, captured: McpServerConfiguration, enabled: Boolean): Boolean {
        val changed = when (captured) {
            is McpServerConfiguration.StreamableHttp -> captured.copy(enabled = enabled)
            is McpServerConfiguration.Stdio -> captured.copy(enabled = enabled)
        }
        return global.settings.editField(captured, { it.mcpServers[name] },
            { it.copy(mcpServers = it.mcpServers + (name to changed)) },
            { owner.ensureActive(); global.ensureActive() },
        )
    }

    public suspend fun readImport(): McpImportPreview {
        owner.ensureActive()
        val baseline = capture()
        val candidates = rpc.getCodexMcpSettings()
        owner.ensureActive()
        check(nextPreview < Long.MAX_VALUE)
        val value = Import(++nextPreview, candidates, baseline)
        preview = value
        return value.present("")
    }

    public fun filterImport(id: Long, filter: String): McpImportPreview? {
        owner.ensureActive()
        return preview?.takeIf { it.id == id }?.present(filter)
    }

    public suspend fun applyImport(id: Long, decisions: Map<String, McpImportDecision>): Boolean {
        owner.ensureActive()
        val value = preview?.takeIf { it.id == id } ?: return false
        val selected = decisions.filterValues { it != McpImportDecision.Skip }
        val configurations = selected.mapValues { (name, decision) ->
            val candidate = value.candidates.singleOrNull { it.serverName == name } as? McpCodexImportCandidate.Supported
                ?: error("The selected declaration is not importable.")
            require(decision != McpImportDecision.Import || value.baseline[name] == null)
            candidate.configuration
        }
        if (configurations.isEmpty()) return true
        val names = configurations.keys
        return global.settings.editField(
            names.associateWith { value.baseline[it] },
            { current -> names.associateWith { current.mcpServers[it] } },
            { current -> current.copy(mcpServers = current.mcpServers + configurations) },
            {
                owner.ensureActive(); global.ensureActive()
                check(preview === value) { "The import preview expired." }
            },
        ).also { if (it && preview === value) preview = null }
    }

    public suspend fun reconnect(name: String) { owner.ensureActive(); rpc.reconnectMcpServer(name) }
    public suspend fun logout(name: String) { owner.ensureActive(); rpc.logoutMcpServer(name) }
    public fun dismissImport() { preview = null }
    override fun close() { preview = null; owner.cancel() }
}

private class Import(
    val id: Long,
    val candidates: List<McpCodexImportCandidate>,
    val baseline: Map<String, McpServerConfiguration>,
) {
    fun present(filter: String): McpImportPreview = McpImportPreview(
        id, filter, candidates.filter { it.serverName.contains(filter, ignoreCase = true) }.map {
            when (it) {
                is McpCodexImportCandidate.Supported -> McpImportItem(
                    it.serverName, it.transport,
                    if (it.serverName in baseline) McpImportItemKind.Conflict else McpImportItemKind.New,
                    it.configuration.enabled, selectable = true,
                )
                is McpCodexImportCandidate.Unsupported -> McpImportItem(
                    it.serverName, it.transport, McpImportItemKind.Unsupported, enabled = null,
                    selectable = false, detail = it.detail,
                )
            }
        },
    )
}
