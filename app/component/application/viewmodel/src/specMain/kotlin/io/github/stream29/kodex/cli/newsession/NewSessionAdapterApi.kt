@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.newsession

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.app.agent.contract.AgentSettingsViewModel
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModelArguments
import io.github.stream29.kodex.app.session.contract.NewSessionViewModelFactory
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModelRegistry
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.openai.ModelInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path

public expect class DefaultNewSessionViewModelFactory(
    createDraft: (NewSessionViewModelArguments, CoroutineScope) -> NewSessionDraftPort,
    models: StateFlow<List<ModelInfo>>,
    sessions: PersistedSessionViewModelRegistry,
    createRuntimeConfiguration: (AgentSettingsViewModel, CoroutineScope) -> RuntimeConfigurationViewModel,
    scope: CoroutineScope,
) : NewSessionViewModelFactory {
    override fun create(arguments: NewSessionViewModelArguments): NewSessionViewModel
}

/**
 * Exact owner-bound draft operations. Submission admits only the captured Composer revision;
 * it must not replay accepted writes or consume a later cwd/edit after cancellation or recovery.
 */
public interface NewSessionDraftPort : AutoCloseable {
    public val settings: StateFlow<KodexAgentSettings>
    public val composer: ComposerViewModel
    public suspend fun <F> editDefaults(
        select: (KodexNewSessionSettings) -> F,
        replace: (KodexNewSessionSettings) -> KodexNewSessionSettings,
    )
    public fun updateWorkingDirectory(path: Path)
    public suspend fun <T> submit(expectedRevision: Long, open: suspend (Int) -> T): T?
    override fun close()
}
