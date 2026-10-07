package io.github.stream29.kodex.app.mcpsettings

import io.github.stream29.kodex.app.settings.contract.McpServerSettingsState
import io.github.stream29.kodex.mcp.contract.McpImportDecision
import io.github.stream29.kodex.mcp.contract.McpImportItemKind
import io.github.stream29.kodex.mcp.contract.McpImportPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

public object DefaultMcpSettingsViewModelFactory : McpSettingsViewModelFactory {
    override fun create(dependencies: McpSettingsDependencies, ownerScope: CoroutineScope): McpSettingsViewModel =
        DefaultMcpSettingsViewModel(dependencies, ownerScope)
}

public fun createMcpSettingsViewModel(
    dependencies: McpSettingsDependencies,
    ownerScope: CoroutineScope,
): McpSettingsViewModel = DefaultMcpSettingsViewModelFactory.create(dependencies, ownerScope)

private class DefaultMcpSettingsViewModel(
    private val dependencies: McpSettingsDependencies,
    ownerScope: CoroutineScope,
) : McpSettingsViewModel {
    private val owner = Job(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + owner)
    private val mutable = MutableStateFlow(McpSettingsState(
        servers = dependencies.servers.value,
        operationFailure = dependencies.operationFailure.value,
    ))
    override val state: StateFlow<McpSettingsState> = mutable.asStateFlow()
    // Rendezvous: one Settings-lifetime consumer, no global replay or durable URL queue.
    private val effectChannel = Channel<McpSettingsEffect>()
    override val effects: Flow<McpSettingsEffect> = effectChannel.receiveAsFlow()
    private var editor: McpEditHandle? = null
    private var deletion: McpServerHandle? = null
    private var imported: McpImportHandle? = null
    private var importRead: Job? = null
    private val logins = mutableMapOf<String, Job>()

    init {
        scope.launch {
            dependencies.servers.collect { servers ->
                val dialog = mutable.value.dialog
                mutable.value = mutable.value.copy(servers = servers, dialog =
                    if (dialog is McpSettingsDialog.Details) {
                        servers.find { it.serverName == dialog.server.serverName }
                            ?.let { dialog.copy(server = it) } ?: McpSettingsDialog.Hidden
                    } else dialog,
                )
            }
        }
        scope.launch {
            dependencies.operationFailure.collect { mutable.value = mutable.value.copy(operationFailure = it) }
        }
        owner.invokeOnCompletion { close() }
        if (!owner.isActive) close()
    }
    private val active: Boolean get() = owner.isActive && !mutable.value.closed
    override fun add() { if (active) openEditor(null) }
    override fun details(serverName: String) {
        if (!active) return
        val server = mutable.value.servers.find { it.serverName == serverName } ?: return
        clearDialog()
        mutable.value = mutable.value.copy(dialog = McpSettingsDialog.Details(McpDialogToken(), server))
    }
    private fun detail(token: McpDialogToken): McpServerSettingsState? =
        (mutable.value.dialog as? McpSettingsDialog.Details)
            ?.takeIf { active && it.token === token }?.server

    override fun edit(token: McpDialogToken) {
        detail(token)?.let { openEditor(it.serverName) }
    }
    private fun openEditor(name: String?) {
        val handle = safely { dependencies.captureEditor(name) } ?: return
        if (!active) { handle.release(); return }
        clearDialog()
        editor = handle
        mutable.value = mutable.value.copy(dialog = McpSettingsDialog.Editing(
            McpDialogToken(), handle.originalName, handle.initialDraft.editorFields(),
        ))
    }
    override fun requestDelete(token: McpDialogToken) {
        val server = detail(token) ?: return
        val handle = safely { dependencies.captureServer(server.serverName) } ?: return
        if (!active) { handle.release(); return }
        clearDialog()
        deletion = handle
        mutable.value = mutable.value.copy(dialog = McpSettingsDialog.Deleting(McpDialogToken(), server))
    }
    override fun updateDraft(token: McpDialogToken, update: (McpEditorDraft) -> McpEditorDraft) {
        val current = mutable.value.dialog as? McpSettingsDialog.Editing ?: return
        if (active && current.token === token) {
            mutable.value = mutable.value.copy(dialog = current.copy(draft = update(current.draft), error = null))
        }
    }
    override fun save(token: McpDialogToken) {
        val current = mutable.value.dialog as? McpSettingsDialog.Editing ?: return
        if (!active || current.token !== token) return
        val draft = try {
            current.draft.validatedDraft()
        } catch (failure: IllegalArgumentException) {
            mutable.value = mutable.value.copy(dialog = current.copy(error =
                failure.message ?: "The MCP server configuration is invalid.",
            ))
            return
        }
        val admission = safely { editor?.save(draft) }
            ?: McpWriteAdmission.Rejected("The MCP configuration could not be queued.")
        if (!active || mutable.value.dialog.tokenOrNull() !== token) return
        when (admission) {
            McpWriteAdmission.Accepted -> clearDialog()
            is McpWriteAdmission.Rejected ->
                mutable.value = mutable.value.copy(dialog = current.copy(error = admission.message))
        }
    }
    override fun confirmDelete(token: McpDialogToken) {
        val current = mutable.value.dialog as? McpSettingsDialog.Deleting ?: return
        if (!active || current.token !== token) return
        val admission = safely { deletion?.delete() }
            ?: McpWriteAdmission.Rejected("The MCP deletion could not be queued.")
        if (!active || mutable.value.dialog.tokenOrNull() !== token) return
        when (admission) {
            McpWriteAdmission.Accepted -> clearDialog()
            is McpWriteAdmission.Rejected ->
                mutable.value = mutable.value.copy(dialog = current.copy(error = admission.message))
        }
    }
    override fun setEnabled(token: McpDialogToken, enabled: Boolean) {
        val server = detail(token) ?: return
        val handle = safely { dependencies.captureServer(server.serverName) } ?: return
        try {
            if (!active) return
            when (val admission = safely { handle.setEnabled(enabled) }) {
                is McpWriteAdmission.Rejected ->
                    dependencies.reportFailure(IllegalStateException(admission.message))
                else -> Unit
            }
        } finally {
            handle.release()
        }
    }
    override fun reconnect(token: McpDialogToken) {
        val name = detail(token)?.serverName ?: return
        command { dependencies.reconnect(name) }
    }
    override fun logout(token: McpDialogToken) {
        val name = detail(token)?.serverName ?: return
        command { dependencies.logout(name) }
    }
    private fun command(action: suspend () -> Unit) {
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { dependencies.reportFailure(failure) }
        }
    }
    override fun login(token: McpDialogToken) {
        val name = detail(token)?.serverName ?: return
        if (logins[name] != null) return
        lateinit var work: Job
        work = scope.launch(start = CoroutineStart.LAZY) {
            var attempt: McpSettingsLogin? = null
            try {
                attempt = dependencies.startLogin(name, scope) ?: return@launch
                if (!active) return@launch
                effectChannel.send(McpSettingsEffect.OpenAuthorizationUrl(
                    name, attempt.authorizationUrl,
                    // Captured job, NOT lookup by name: late browser failure cancels only this work.
                    cancel = { work.cancel() },
                ))
                attempt.awaitCompletion()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                dependencies.reportFailure(failure)
            } finally {
                attempt?.cancel()
            }
        }
        logins[name] = work
        work.invokeOnCompletion { if (logins[name] === work) logins.remove(name) }
        work.start()
    }
    override fun cancelLogin(token: McpDialogToken) {
        detail(token)?.let { logins[it.serverName]?.cancel() }
    }
    override fun importCodex() {
        if (!active) return
        clearDialog()
        val token = McpDialogToken()
        mutable.value = mutable.value.copy(dialog = McpSettingsDialog.ImportLoading(token))
        importRead = scope.launch {
            var handle: McpImportHandle? = null
            try {
                handle = dependencies.readImport()
                if (!active || mutable.value.dialog.tokenOrNull() !== token) return@launch
                imported = handle
                val preview = handle.preview
                mutable.value = mutable.value.copy(dialog = McpSettingsDialog.ImportPreview(
                    token, preview, preview.defaultDecisions(),
                ))
                handle = null // ownership transferred to imported; finally only releases late results.
            } catch (cancelled: CancellationException) {
                // An adapter may cancel while the owner remains alive; don't leave eternal Loading.
                if (active && mutable.value.dialog.tokenOrNull() === token) {
                    mutable.value = mutable.value.copy(dialog = McpSettingsDialog.Hidden)
                }
                throw cancelled
            } catch (failure: Throwable) {
                dependencies.reportFailure(failure)
                if (active && mutable.value.dialog.tokenOrNull() === token) {
                    mutable.value = mutable.value.copy(dialog = McpSettingsDialog.ImportFailed(token))
                }
            } finally {
                handle?.release()
            }
        }
    }
    override fun retryImport(token: McpDialogToken) {
        val current = mutable.value.dialog as? McpSettingsDialog.ImportFailed ?: return
        if (active && current.token === token) importCodex()
    }
    private fun preview(token: McpDialogToken): McpSettingsDialog.ImportPreview? =
        (mutable.value.dialog as? McpSettingsDialog.ImportPreview)
            ?.takeIf { active && it.token === token }

    override fun filterImport(token: McpDialogToken, filter: String) {
        val current = preview(token) ?: return
        val handle = imported ?: return
        mutable.value = mutable.value.copy(dialog = current.copy(preview = handle.filter(filter)))
    }
    override fun toggleImport(token: McpDialogToken, serverName: String) {
        val current = preview(token) ?: return
        val item = current.preview.items.find { it.serverName == serverName && it.selectable } ?: return
        val previous = current.decisions[serverName] ?: McpImportDecision.Skip
        val selected = if (previous != McpImportDecision.Skip) McpImportDecision.Skip else when (item.kind) {
            McpImportItemKind.New -> McpImportDecision.Import
            McpImportItemKind.Conflict -> McpImportDecision.Replace
            McpImportItemKind.Unsupported -> return
        }
        mutable.value = mutable.value.copy(dialog = current.copy(
            decisions = current.decisions + (serverName to selected), error = null,
        ))
    }
    override fun selectAllImports(token: McpDialogToken) {
        val current = preview(token) ?: return
        val handle = imported ?: return
        mutable.value = mutable.value.copy(dialog = current.copy(
            decisions = handle.preview.defaultDecisions(), error = null,
        ))
    }
    override fun clearImports(token: McpDialogToken) {
        val current = preview(token) ?: return
        mutable.value = mutable.value.copy(dialog = current.copy(
            decisions = current.decisions.mapValues { McpImportDecision.Skip }, error = null,
        ))
    }
    override fun applyImport(token: McpDialogToken) {
        val current = preview(token) ?: return
        if (current.decisions.values.none { it != McpImportDecision.Skip }) return
        val admission = safely { imported?.apply(current.decisions.toMap()) }
            ?: McpWriteAdmission.Rejected("The import could not be queued.")
        if (!active || mutable.value.dialog.tokenOrNull() !== token) return
        when (admission) {
            McpWriteAdmission.Accepted -> clearDialog()
            is McpWriteAdmission.Rejected ->
                mutable.value = mutable.value.copy(dialog = current.copy(error = admission.message))
        }
    }
    override fun dismiss(token: McpDialogToken) {
        if (active && mutable.value.dialog.tokenOrNull() === token) clearDialog()
    }
    override fun hidePage() { if (active) clearDialog() }
    override fun dismissFailure() { if (active) dependencies.dismissFailure() }
    private fun clearDialog() {
        // Invalidate token BEFORE cancelling: cancelled or noncooperative old reads cannot reopen UI.
        mutable.value = mutable.value.copy(dialog = McpSettingsDialog.Hidden)
        importRead?.cancel()
        importRead = null
        imported?.release()
        imported = null
        editor?.release()
        editor = null
        deletion?.release()
        deletion = null
    }
    private inline fun <T> safely(action: () -> T): T? = try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        dependencies.reportFailure(failure)
        null
    }
    override fun close() {
        if (mutable.value.closed) return
        clearDialog()
        mutable.value = mutable.value.copy(closed = true)
        effectChannel.close()
        owner.cancel()
    }
}

private fun McpSettingsDialog.tokenOrNull(): McpDialogToken? = when (this) {
    McpSettingsDialog.Hidden -> null
    is McpSettingsDialog.Details -> token
    is McpSettingsDialog.Editing -> token
    is McpSettingsDialog.Deleting -> token
    is McpSettingsDialog.ImportLoading -> token
    is McpSettingsDialog.ImportFailed -> token
    is McpSettingsDialog.ImportPreview -> token
}

private fun McpImportPreview.defaultDecisions(): Map<String, McpImportDecision> =
    items.associate { item -> item.serverName to when {
        !item.selectable -> McpImportDecision.Skip
        item.kind == McpImportItemKind.New -> McpImportDecision.Import
        item.kind == McpImportItemKind.Conflict -> McpImportDecision.Replace
        else -> McpImportDecision.Skip
    } }
