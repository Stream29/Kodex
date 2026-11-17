package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.app.mcpsettings.McpImportHandle
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsState
import io.github.stream29.kodex.app.settings.contract.SettingsWriteAdmission
import io.github.stream29.kodex.app.settings.toSettingsState
import io.github.stream29.kodex.mcp.contract.McpCodexImportCandidate
import io.github.stream29.kodex.mcp.contract.McpImportDecision
import io.github.stream29.kodex.mcp.contract.McpImportItem
import io.github.stream29.kodex.mcp.contract.McpImportItemKind
import io.github.stream29.kodex.mcp.contract.McpImportPreview
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.contract.McpServerDraft
import io.github.stream29.kodex.mcp.contract.ReservedMcpServerNames
import io.github.stream29.kodex.mcp.contract.toConfiguration
import io.github.stream29.kodex.mcp.contract.validatedName
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow

internal class RpcMcpSettings(
    private val global: RpcGlobalSettings,
    private val rpc: GlobalRpc,
    scope: CoroutineScope,
) : AutoCloseable {
    private val owner = Job(scope.coroutineContext[Job])
    val servers: StateFlow<List<McpServerSettingsState>> =
        global.mcp.projectState { values -> values.map { it.toSettingsState() } }
    private var nextPreview = 0L

    fun capture(): Map<String, McpServerConfiguration> {
        owner.ensureActive()
        global.ensureActive()
        return global.settings.value.mcpServers
    }

    suspend fun save(
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

    suspend fun delete(name: String, captured: McpServerConfiguration): Boolean =
        global.settings.editField(captured, { it.mcpServers[name] }, { it.copy(mcpServers = it.mcpServers - name) }) {
            owner.ensureActive(); global.ensureActive()
        }

    suspend fun setEnabled(name: String, captured: McpServerConfiguration, enabled: Boolean): Boolean {
        val changed = when (captured) {
            is McpServerConfiguration.StreamableHttp -> captured.copy(enabled = enabled)
            is McpServerConfiguration.Stdio -> captured.copy(enabled = enabled)
        }
        return global.settings.editField(captured, { it.mcpServers[name] },
            { it.copy(mcpServers = it.mcpServers + (name to changed)) },
            { owner.ensureActive(); global.ensureActive() },
        )
    }

    suspend fun readImport(
        accept: (suspend () -> Unit) -> Boolean,
    ): McpImportHandle {
        owner.ensureActive()
        val baseline = capture()
        val candidates = rpc.getCodexMcpSettings()
        owner.ensureActive()
        check(nextPreview < Long.MAX_VALUE)
        val value = CodexMcpImport(++nextPreview, candidates, baseline)
        return object : McpImportHandle {
            private var released = false
            override val preview: McpImportPreview = value.present("")
            override fun filter(filter: String): McpImportPreview = value.present(filter)
            override fun apply(decisions: Map<String, McpImportDecision>): SettingsWriteAdmission {
                if (released) return SettingsWriteAdmission.Rejected("The import preview is no longer active.")
                val capturedDecisions = decisions.toMap()
                val accepted = accept {
                    check(applyImport(value, capturedDecisions)) {
                        "The import target changed. Open a new preview."
                    }
                }
                if (accepted) released = true
                return if (accepted) SettingsWriteAdmission.Accepted
                else SettingsWriteAdmission.Rejected("Settings is no longer accepting edits.")
            }
            override fun release() { released = true }
        }
    }

    private suspend fun applyImport(value: CodexMcpImport, decisions: Map<String, McpImportDecision>): Boolean {
        owner.ensureActive()
        val configurations = value.resolveConfigurations(decisions)
        if (configurations.isEmpty()) return true
        val names = configurations.keys
        return global.settings.editField(
            names.associateWith { value.baseline[it] },
            { current -> names.associateWith { current.mcpServers[it] } },
            { current -> current.copy(mcpServers = current.mcpServers + configurations) },
            {
                owner.ensureActive(); global.ensureActive()
            },
        )
    }

    suspend fun reconnect(name: String) { owner.ensureActive(); rpc.reconnectMcpServer(name) }
    suspend fun logout(name: String) { owner.ensureActive(); rpc.logoutMcpServer(name) }
    override fun close() { owner.cancel() }
}

internal class CodexMcpImport(
    val id: Long,
    val candidates: List<McpCodexImportCandidate>,
    val baseline: Map<String, McpServerConfiguration>,
) {
    fun present(filter: String): McpImportPreview = McpImportPreview(
        id,
        filter,
        candidates.filter { it.serverName.contains(filter, ignoreCase = true) }.map {
            when (it) {
                is McpCodexImportCandidate.Supported -> {
                    val trimmedName = it.serverName.trim()
                    if (trimmedName in ReservedMcpServerNames) {
                        McpImportItem(
                            serverName = it.serverName,
                            transport = it.transport,
                            kind = McpImportItemKind.Unsupported,
                            enabled = null,
                            selectable = false,
                            detail = "Server name '$trimmedName' is reserved by Kodex.",
                        )
                    } else {
                        McpImportItem(
                            serverName = it.serverName,
                            transport = it.transport,
                            kind = if (it.serverName in baseline) McpImportItemKind.Conflict else McpImportItemKind.New,
                            enabled = it.configuration.enabled,
                            selectable = true,
                        )
                    }
                }
                is McpCodexImportCandidate.Unsupported -> McpImportItem(
                    serverName = it.serverName,
                    transport = it.transport,
                    kind = McpImportItemKind.Unsupported,
                    enabled = null,
                    selectable = false,
                    detail = it.detail,
                )
            }
        },
    )

    fun resolveConfigurations(
        decisions: Map<String, McpImportDecision>,
    ): Map<String, McpServerConfiguration> {
        val selected = decisions.filterValues { it != McpImportDecision.Skip }
        return selected.mapValues { (name, decision) ->
            val trimmedName = name.trim()
            require(trimmedName !in ReservedMcpServerNames) {
                "Server name '$trimmedName' is reserved by Kodex."
            }
            val candidate = candidates.singleOrNull { it.serverName == name } as? McpCodexImportCandidate.Supported
                ?: error("The selected declaration is not importable.")
            require(decision != McpImportDecision.Import || baseline[name] == null)
            candidate.configuration
        }
    }
}
