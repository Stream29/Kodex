package io.github.stream29.kodex.app.hooksettings

import io.github.stream29.kodex.rpc.models.NotificationHook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

public object DefaultHookSettingsViewModelFactory : HookSettingsViewModelFactory {
    override fun create(dependencies: HookSettingsDependencies, ownerScope: CoroutineScope): HookSettingsViewModel =
        DefaultHookSettingsViewModel(dependencies, ownerScope)
}

public fun createHookSettingsViewModel(
    dependencies: HookSettingsDependencies,
    ownerScope: CoroutineScope,
): HookSettingsViewModel = DefaultHookSettingsViewModelFactory.create(dependencies, ownerScope)

private class DefaultHookSettingsViewModel(
    private val dependencies: HookSettingsDependencies,
    ownerScope: CoroutineScope,
) : HookSettingsViewModel {
    private val owner = Job(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + owner)
    private val mutable = MutableStateFlow(HookSettingsState(
        hooks = dependencies.hooks.value,
        operationFailure = dependencies.operationFailure.value,
    ))
    override val state: StateFlow<HookSettingsState> = mutable.asStateFlow()
    private var editor: HookEditHandle? = null

    init {
        scope.launch {
            dependencies.hooks.collect { hooks ->
                val dialog = mutable.value.dialog
                mutable.value = mutable.value.copy(hooks = hooks, dialog =
                    if (dialog is HookSettingsDialog.Details) {
                        hooks.find { it.name == dialog.hook.name }?.let { dialog.copy(hook = it) }
                            ?: HookSettingsDialog.Hidden
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
    override fun details(name: String) {
        if (!active) return
        val hook = mutable.value.hooks.find { it.name == name } ?: return
        clearDialog()
        mutable.value = mutable.value.copy(dialog = HookSettingsDialog.Details(HookDialogToken(), hook))
    }
    override fun edit(token: HookDialogToken) {
        val current = mutable.value.dialog as? HookSettingsDialog.Details ?: return
        if (active && current.token === token) openEditor(current.hook.name)
    }
    private fun openEditor(name: String?) {
        val handle = safely { dependencies.captureEditor(name) } ?: return
        if (!active) { handle.release(); return }
        clearDialog()
        editor = handle
        val original = handle.original
        mutable.value = mutable.value.copy(dialog = HookSettingsDialog.Editing(
            HookDialogToken(), original?.name,
            original?.let { HookEditorDraft(it.name, it.command, it.types) } ?: HookEditorDraft(),
        ))
    }
    override fun requestDelete(token: HookDialogToken) {
        val current = mutable.value.dialog as? HookSettingsDialog.Details ?: return
        if (!active || current.token !== token) return
        mutable.value = mutable.value.copy(dialog = HookSettingsDialog.Deleting(HookDialogToken(), current.hook))
    }
    override fun updateDraft(token: HookDialogToken, update: (HookEditorDraft) -> HookEditorDraft) {
        val current = mutable.value.dialog as? HookSettingsDialog.Editing ?: return
        if (active && current.token === token) {
            mutable.value = mutable.value.copy(dialog = current.copy(draft = update(current.draft), error = null))
        }
    }
    override fun save(token: HookDialogToken) {
        val current = mutable.value.dialog as? HookSettingsDialog.Editing ?: return
        if (!active || current.token !== token) return
        val draft = current.draft
        val updated = try {
            val name = draft.name.trim()
            require(name.isNotEmpty()) { "Hook name is required." }
            require(draft.command.isNotBlank()) { "Command is required." }
            NotificationHook(name, draft.types, draft.command)
        } catch (failure: IllegalArgumentException) {
            mutable.value = mutable.value.copy(dialog = current.copy(error = failure.message))
            return
        }
        val admission = safely { editor?.save(updated) }
            ?: HookWriteAdmission.Rejected("The Hook configuration could not be queued.")
        if (!active || mutable.value.dialog.tokenOrNull() !== token) return
        when (admission) {
            HookWriteAdmission.Accepted -> clearDialog()
            is HookWriteAdmission.Rejected ->
                mutable.value = mutable.value.copy(dialog = current.copy(error = admission.message))
        }
    }
    override fun confirmDelete(token: HookDialogToken) {
        val current = mutable.value.dialog as? HookSettingsDialog.Deleting ?: return
        if (!active || current.token !== token) return
        val admission = safely { dependencies.delete(current.original) }
            ?: HookWriteAdmission.Rejected("The Hook deletion could not be queued.")
        if (!active || mutable.value.dialog.tokenOrNull() !== token) return
        when (admission) {
            HookWriteAdmission.Accepted -> clearDialog()
            is HookWriteAdmission.Rejected ->
                mutable.value = mutable.value.copy(dialog = current.copy(error = admission.message))
        }
    }
    override fun dismiss(token: HookDialogToken) {
        if (active && token === mutable.value.dialog.tokenOrNull()) clearDialog()
    }
    override fun hidePage() { if (active) clearDialog() }
    override fun dismissFailure() { if (active) dependencies.dismissFailure() }
    private fun clearDialog() {
        editor?.release()
        editor = null
        mutable.value = mutable.value.copy(dialog = HookSettingsDialog.Hidden)
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
        owner.cancel()
    }
}

private fun HookSettingsDialog.tokenOrNull(): HookDialogToken? = when (this) {
    HookSettingsDialog.Hidden -> null
    is HookSettingsDialog.Details -> token
    is HookSettingsDialog.Editing -> token
    is HookSettingsDialog.Deleting -> token
}
