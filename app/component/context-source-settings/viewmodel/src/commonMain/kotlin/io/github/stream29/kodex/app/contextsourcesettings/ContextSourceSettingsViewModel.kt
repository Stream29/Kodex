package io.github.stream29.kodex.app.contextsourcesettings

import io.github.stream29.kodex.agentcontext.contract.AgentContextCustomSource
import io.github.stream29.kodex.agentcontext.contract.AgentContextSourceSettings
import io.github.stream29.kodex.agentcontext.contract.ContextPathEvaluation
import io.github.stream29.kodex.app.settings.contract.BuiltInContextSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

public actual fun createContextSourceSettingsViewModel(
    dependencies: ContextSourceSettingsDependencies, ownerScope: CoroutineScope,
): ContextSourceSettingsViewModel = DefaultContextSourceSettingsViewModel(dependencies, ownerScope)

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
    private var pending: PendingEvaluation? = null
    private var submitting: PendingEvaluation? = null
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
        if (!active) return
        cancelEvaluation()
        mutable.value = mutable.value.copy(
            dialog = ContextSourceSettingsDialog.Adding(ContextSourceDialogToken()),
        )
    }

    private fun adding(token: ContextSourceDialogToken): ContextSourceSettingsDialog.Adding? =
        (mutable.value.dialog as? ContextSourceSettingsDialog.Adding)
            ?.takeIf { active && it.token === token }

    override fun updateDraft(token: ContextSourceDialogToken, path: String) {
        val current = adding(token) ?: return
        if (submitting?.token === token) return
        cancelEvaluation()
        mutable.value = mutable.value.copy(dialog = current.copy(draft = path, error = null))
    }

    override fun save(token: ContextSourceDialogToken) {
        val current = adding(token) ?: return
        if (submitting != null || pending != null) return
        val request = PendingEvaluation(
            token, current.draft, current.draft.trim(), dependencies.sources.value.customSources.toList(),
        )
        pending = request
        request.job = scope.launch(start = CoroutineStart.LAZY) { evaluate(request) }
        request.job?.start()
    }

    private suspend fun evaluate(request: PendingEvaluation) {
        val token = request.token
        try {
            val result = dependencies.pathPolicy.evaluate(request.input, request.original.map { it.path })
            currentCoroutineContext().ensureActive()
            if (!current(request)) return
            if (!unchangedBaseline(request)) return
            val evaluated = when (result) {
                is ContextPathEvaluation.Evaluated -> result
                is ContextPathEvaluation.Failure -> {
                    error(token, "The context path could not be evaluated. Try again.")
                    return
                }
            }
            check(evaluated.originalPaths.size == request.original.size) {
                "Context path evaluation returned a different number of original paths."
            }
            val normalized = evaluated.candidate.lexicalKey
            if (normalized == null) {
                error(token, "Enter an absolute path, ~, or ~/path.")
                return
            }
            if (evaluated.candidate.matchesBuiltIn) {
                error(token, "This path is already a built-in context source.")
                return
            }
            val duplicate = evaluated.originalPaths.indexOfFirst { it.lexicalKey == normalized }
            val updated = if (duplicate < 0) request.original + AgentContextCustomSource(request.input)
            else request.original.mapIndexed { index, source ->
                if (index == duplicate) source.copy(enabled = true) else source
            }
            // From here admission is synchronous; accepted writes belong to the application queue.
            submitting = request
            when (val admission = dependencies.replaceCustomSources(request.original, updated.toList())) {
                ContextSourceWriteAdmission.Accepted -> dismiss(token)
                is ContextSourceWriteAdmission.Rejected -> error(token, admission.message)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (current(request) && unchangedBaseline(request)) {
                dependencies.reportFailure(failure)
                error(token, "The context source could not be queued.")
            }
        } finally {
            if (pending === request) pending = null
            if (submitting === request) submitting = null
        }
    }

    private fun current(request: PendingEvaluation): Boolean =
        pending === request && adding(request.token)?.draft == request.draft

    private fun unchangedBaseline(request: PendingEvaluation): Boolean {
        if (dependencies.sources.value.customSources == request.original) return true
        error(request.token, "Context sources changed. Try adding this path again.")
        return false
    }

    private fun cancelEvaluation() {
        val request = pending ?: return
        pending = null
        if (submitting !== request) request.job?.cancel()
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
        if (adding(token) == null) return
        cancelEvaluation()
        mutable.value = mutable.value.copy(dialog = ContextSourceSettingsDialog.Hidden)
    }
    override fun hidePage() {
        if (!active) return
        cancelEvaluation()
        mutable.value = mutable.value.copy(dialog = ContextSourceSettingsDialog.Hidden)
    }
    override fun dismissFailure() { if (active) dependencies.dismissFailure() }
    override fun close() {
        if (mutable.value.closed) return
        mutable.value = mutable.value.copy(dialog = ContextSourceSettingsDialog.Hidden, closed = true)
        cancelEvaluation()
        owner.cancel()
    }
}

private class PendingEvaluation(
    val token: ContextSourceDialogToken,
    val draft: String,
    val input: String,
    val original: List<AgentContextCustomSource>,
) {
    var job: Job? = null
}

private fun AgentContextSourceSettings.enabled(source: BuiltInContextSource): Boolean = when (source) {
    BuiltInContextSource.AgentsHome -> agentsHomeEnabled
    BuiltInContextSource.KodexHome -> kodexHomeEnabled
    BuiltInContextSource.CodexHome -> codexHomeEnabled
    BuiltInContextSource.GitRoot -> gitRootEnabled
    BuiltInContextSource.WorkingDirectory -> workingDirectoryEnabled
}
