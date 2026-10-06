package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.agentcontext.contract.AgentContextCustomSource
import io.github.stream29.kodex.agentcontext.contract.AgentContextSourceSettings
import io.github.stream29.kodex.app.applicationpreferences.ApplicationPreferencesDependencies
import io.github.stream29.kodex.app.applicationpreferences.PreferencesWriteAdmission
import io.github.stream29.kodex.app.contextsourcesettings.ContextSourcePathPolicy
import io.github.stream29.kodex.app.contextsourcesettings.ContextSourceSettingsDependencies
import io.github.stream29.kodex.app.contextsourcesettings.ContextSourceWriteAdmission
import io.github.stream29.kodex.app.sessiontitlesettings.SessionTitleSettingsDependencies
import io.github.stream29.kodex.app.sessiontitlesettings.SessionTitleWriteAdmission
import io.github.stream29.kodex.app.settings.contract.BuiltInContextSource
import io.github.stream29.kodex.cli.sessiontitle.DefaultSessionTitleModel
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.rpc.models.BackendSettings
import io.github.stream29.kodex.utils.osenvironment.requireUserHomeDirectory
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path

/** Every admitted field edit uses the original application queue and backend CAS rule. */
internal class RpcConfigurationWrites(
    private val global: RpcGlobalSettings,
    private val accept: (suspend () -> Unit) -> Boolean,
) {
    fun <F> edit(
        expected: F,
        select: (BackendSettings) -> F,
        update: (BackendSettings) -> BackendSettings,
    ): Boolean = accept {
        global.settings.editField(expected, select, update, global::ensureActive)
    }
}

/** Captures only the frontend environment for comparison; never opens a user's context files. */
private class FrontendContextPathPolicy : ContextSourcePathPolicy {
    private val home: Path by lazy { Path(requireUserHomeDirectory()) }
    override val builtInNormalizedPaths: Set<String>
        get() = setOf(".agents", ".kodex", ".codex").map { Path(home, it).toString() }.toSet()

    override fun normalize(path: String): String? {
        if (path.isBlank() || '$' in path) return null
        // Retain the existing frontend-home interpretation, even for absolute input.
        val capturedHome = home
        return when {
            path == "~" -> capturedHome
            path.startsWith("~/") -> Path(capturedHome, path.substring(2))
            else -> Path(path).takeIf { it.isAbsolute }
        }?.toString()
    }
}

internal class RpcContextSourceSettingsDependencies(
    private val global: RpcGlobalSettings,
    override val sources: StateFlow<AgentContextSourceSettings>,
    private val writes: RpcConfigurationWrites,
) : ContextSourceSettingsDependencies {
    override val pathPolicy: ContextSourcePathPolicy = FrontendContextPathPolicy()
    override val operationFailure: StateFlow<Boolean> = global.operationFailure

    override fun setBuiltInEnabled(
        source: BuiltInContextSource, expected: Boolean, enabled: Boolean,
    ): ContextSourceWriteAdmission = admission(writes.edit(expected, { it.contextSources.enabled(source) }) {
        it.copy(contextSources = it.contextSources.withEnabled(source, enabled))
    })

    override fun replaceCustomSources(
        expected: List<AgentContextCustomSource>, updated: List<AgentContextCustomSource>,
    ): ContextSourceWriteAdmission {
        val baseline = expected.toList()
        val payload = updated.toList()
        return admission(writes.edit(baseline, { it.contextSources.customSources }) {
            it.copy(contextSources = it.contextSources.copy(customSources = payload))
        })
    }

    override fun setCustomEnabled(
        original: AgentContextCustomSource, enabled: Boolean,
    ): ContextSourceWriteAdmission = admission(writes.edit(original, {
        it.contextSources.customSources.find { item -> item.path == original.path }
    }) { value ->
        value.copy(contextSources = value.contextSources.copy(customSources =
            value.contextSources.customSources.map {
                if (it.path == original.path) it.copy(enabled = enabled) else it
            },
        ))
    })

    override fun removeCustom(original: AgentContextCustomSource): ContextSourceWriteAdmission =
        admission(writes.edit(original, {
            it.contextSources.customSources.find { item -> item.path == original.path }
        }) { value ->
            value.copy(contextSources = value.contextSources.copy(customSources =
                value.contextSources.customSources.filterNot { it.path == original.path },
            ))
        })

    override fun reportFailure(failure: Throwable) { global.reportOperationFailure(failure) }
    override fun dismissFailure() { global.dismissOperationFailure() }

    private fun admission(accepted: Boolean): ContextSourceWriteAdmission =
        if (accepted) ContextSourceWriteAdmission.Accepted
        else ContextSourceWriteAdmission.Rejected("Settings is no longer accepting edits.")
}

internal class RpcSessionTitleSettingsDependencies(
    private val global: RpcGlobalSettings,
    override val settings: StateFlow<SessionTitleSettings>,
    private val writes: RpcConfigurationWrites,
) : SessionTitleSettingsDependencies {
    override val models: StateFlow<List<ModelInfo>> = global.models
    override val defaultModel: OpenAiModelId = DefaultSessionTitleModel
    override val operationFailure: StateFlow<Boolean> = global.operationFailure
    override fun setEnabled(expected: Boolean, enabled: Boolean): SessionTitleWriteAdmission =
        admission(writes.edit(expected, { it.sessionTitle.enabled }) {
            it.copy(sessionTitle = it.sessionTitle.copy(enabled = enabled))
        })
    override fun setModel(expected: OpenAiModelId?, model: OpenAiModelId?): SessionTitleWriteAdmission =
        admission(writes.edit(expected, { it.sessionTitle.model }) {
            it.copy(sessionTitle = it.sessionTitle.copy(model = model))
        })
    override fun setReasoningEffort(
        expected: ReasoningEffort, reasoningEffort: ReasoningEffort,
    ): SessionTitleWriteAdmission = admission(writes.edit(expected, { it.sessionTitle.reasoningEffort }) {
        it.copy(sessionTitle = it.sessionTitle.copy(reasoningEffort = reasoningEffort))
    })
    override fun reportFailure(failure: Throwable) { global.reportOperationFailure(failure) }
    override fun dismissFailure() { global.dismissOperationFailure() }
    private fun admission(accepted: Boolean): SessionTitleWriteAdmission =
        if (accepted) SessionTitleWriteAdmission.Accepted else SessionTitleWriteAdmission.Rejected
}

internal class RpcApplicationPreferencesDependencies(
    private val global: RpcGlobalSettings,
    override val newLineKey: StateFlow<NewLineKey>,
    private val accept: (suspend () -> Unit) -> Boolean,
) : ApplicationPreferencesDependencies {
    override val widths: StateFlow<Pair<Int, Int>> = global.sidebarWidths
    override val operationFailure: StateFlow<Boolean> = global.operationFailure
    override fun setLeftWidth(columns: Int) { global.resizeSidebars(columns, widths.value.second) }
    override fun setRightWidth(columns: Int) { global.resizeSidebars(widths.value.first, columns) }
    override fun setNewLineKey(newLineKey: NewLineKey): PreferencesWriteAdmission =
        if (accept { global.frontend.update { it.copy(newLineKey = newLineKey) } })
            PreferencesWriteAdmission.Accepted else PreferencesWriteAdmission.Rejected
    override fun reportFailure(failure: Throwable) { global.reportOperationFailure(failure) }
    override fun dismissFailure() { global.dismissOperationFailure() }
}

private fun AgentContextSourceSettings.enabled(source: BuiltInContextSource): Boolean = when (source) {
    BuiltInContextSource.AgentsHome -> agentsHomeEnabled
    BuiltInContextSource.KodexHome -> kodexHomeEnabled
    BuiltInContextSource.CodexHome -> codexHomeEnabled
    BuiltInContextSource.GitRoot -> gitRootEnabled
    BuiltInContextSource.WorkingDirectory -> workingDirectoryEnabled
}

private fun AgentContextSourceSettings.withEnabled(
    source: BuiltInContextSource, enabled: Boolean,
): AgentContextSourceSettings = when (source) {
    BuiltInContextSource.AgentsHome -> copy(agentsHomeEnabled = enabled)
    BuiltInContextSource.KodexHome -> copy(kodexHomeEnabled = enabled)
    BuiltInContextSource.CodexHome -> copy(codexHomeEnabled = enabled)
    BuiltInContextSource.GitRoot -> copy(gitRootEnabled = enabled)
    BuiltInContextSource.WorkingDirectory -> copy(workingDirectoryEnabled = enabled)
}
