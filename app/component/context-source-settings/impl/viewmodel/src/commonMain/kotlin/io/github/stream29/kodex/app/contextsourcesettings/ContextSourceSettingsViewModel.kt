package io.github.stream29.kodex.app.contextsourcesettings

import io.github.stream29.kodex.agentcontext.contract.AgentContextCustomSource
import io.github.stream29.kodex.agentcontext.contract.AgentContextSourceSettings
import io.github.stream29.kodex.app.settings.contract.BuiltInContextSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

public object DefaultContextSourceSettingsViewModelFactory : ContextSourceSettingsViewModelFactory {
    override fun create(
        dependencies: ContextSourceSettingsDependencies, ownerScope: CoroutineScope,
    ): ContextSourceSettingsViewModel = DefaultContextSourceSettingsViewModel(dependencies, ownerScope)
}

public fun createContextSourceSettingsViewModel(
    dependencies: ContextSourceSettingsDependencies, ownerScope: CoroutineScope,
): ContextSourceSettingsViewModel = DefaultContextSourceSettingsViewModelFactory.create(dependencies, ownerScope)

private class DefaultContextSourceSettingsViewModel(
    private val dependencies: ContextSourceSettingsDependencies,
    ownerScope: CoroutineScope,
) : ContextSourceSettingsViewModel {
    private val owner = Job(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + owner)
    private val mutable = MutableStateFlow(ContextSourceSettingsState(
        sources = dependencies.sources.value,
        operationFailure = dependencies.operationFailure.value,
    ))
    override val state: StateFlow<ContextSourceSettingsState> = mutable.asStateFlow()
    private var submitting: ContextSourceDialogToken? = null
    private val active: Boolean get() = owner.isActive && !mutable.value.closed

    init {
        scope.launch {
            dependencies.sources.collect { if (active) mutable.value = mutable.value.copy(sources = it) }
        }
        scope.launch {
            dependencies.operationFailure.collect {
                if (active) mutable.value = mutable.value.copy(operationFailure = it)
            }
        }
        owner.invokeOnCompletion { close() }
        if (!owner.isActive) close()
    }

    override fun add() {
        if (active) mutable.value = mutable.value.copy(
            dialog = ContextSourceSettingsDialog.Adding(ContextSourceDialogToken()),
        )
    }

    private fun adding(token: ContextSourceDialogToken): ContextSourceSettingsDialog.Adding? =
        (mutable.value.dialog as? ContextSourceSettingsDialog.Adding)
            ?.takeIf { active && it.token === token }

    override fun updateDraft(token: ContextSourceDialogToken, path: String) {
        val current = adding(token) ?: return
        if (submitting === token) return
        mutable.value = mutable.value.copy(dialog = current.copy(draft = path, error = null))
    }

    override fun save(token: ContextSourceDialogToken) {
        val current = adding(token) ?: return
        if (submitting != null) return
        // Guard policy and admission against reentrant/double submission before crossing either port.
        submitting = token
        try {
            val input = current.draft.trim()
            val normalized = dependencies.pathPolicy.normalize(input)
            if (normalized == null) {
                error(token, "Enter an absolute path, ~, or ~/path.")
                return
            }
            if (normalized in dependencies.pathPolicy.builtInNormalizedPaths) {
                error(token, "This path is already a built-in context source.")
                return
            }
            val original = dependencies.sources.value.customSources.toList()
            val duplicate = original.indexOfFirst { dependencies.pathPolicy.normalize(it.path) == normalized }
            val updated = if (duplicate < 0) original + AgentContextCustomSource(input)
            else original.mapIndexed { index, source ->
                if (index == duplicate) source.copy(enabled = true) else source
            }
            // A policy callback may have closed/replaced this interaction; never admit its stale draft.
            if (adding(token) == null) return
            when (val admission = dependencies.replaceCustomSources(original, updated.toList())) {
                ContextSourceWriteAdmission.Accepted -> dismiss(token)
                is ContextSourceWriteAdmission.Rejected -> error(token, admission.message)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            dependencies.reportFailure(failure)
            error(token, "The context source could not be queued.")
        } finally {
            submitting = null
        }
    }

    private fun error(token: ContextSourceDialogToken, message: String) {
        val current = adding(token) ?: return
        mutable.value = mutable.value.copy(dialog = current.copy(error = message))
    }

    override fun setBuiltInEnabled(source: BuiltInContextSource, enabled: Boolean) {
        if (!active) return
        val expected = dependencies.sources.value.enabled(source)
        admit { dependencies.setBuiltInEnabled(source, expected, enabled) }
    }

    override fun setCustomEnabled(path: String, enabled: Boolean) {
        if (!active) return
        val original = dependencies.sources.value.customSources.find { it.path == path } ?: return
        admit { dependencies.setCustomEnabled(original, enabled) }
    }

    override fun removeCustom(path: String) {
        if (!active) return
        val original = dependencies.sources.value.customSources.find { it.path == path } ?: return
        admit { dependencies.removeCustom(original) }
    }

    private fun admit(write: () -> ContextSourceWriteAdmission) {
        try {
            if (write() is ContextSourceWriteAdmission.Rejected) {
                dependencies.reportFailure(IllegalStateException("Context source admission rejected."))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            dependencies.reportFailure(failure)
        }
    }

    override fun dismiss(token: ContextSourceDialogToken) {
        if (adding(token) != null) mutable.value = mutable.value.copy(dialog = ContextSourceSettingsDialog.Hidden)
    }
    override fun hidePage() {
        if (active) mutable.value = mutable.value.copy(dialog = ContextSourceSettingsDialog.Hidden)
    }
    override fun dismissFailure() { if (active) dependencies.dismissFailure() }
    override fun close() {
        if (mutable.value.closed) return
        mutable.value = mutable.value.copy(dialog = ContextSourceSettingsDialog.Hidden, closed = true)
        owner.cancel()
    }
}

private fun AgentContextSourceSettings.enabled(source: BuiltInContextSource): Boolean = when (source) {
    BuiltInContextSource.AgentsHome -> agentsHomeEnabled
    BuiltInContextSource.KodexHome -> kodexHomeEnabled
    BuiltInContextSource.CodexHome -> codexHomeEnabled
    BuiltInContextSource.GitRoot -> gitRootEnabled
    BuiltInContextSource.WorkingDirectory -> workingDirectoryEnabled
}
