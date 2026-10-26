@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.newsession

import io.github.stream29.kodex.app.agent.contract.AgentSettingsViewModel
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModelArguments
import io.github.stream29.kodex.app.session.contract.NewSessionViewModelFactory
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModelRegistry
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
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

/** Reusable frontend child; only cwd/Composer are local, model configuration edits shared defaults. */
public expect class RpcNewSessionViewModel internal constructor(
    arguments: NewSessionViewModelArguments,
    createDraft: (NewSessionViewModelArguments, CoroutineScope) -> NewSessionDraftPort,
    models: StateFlow<List<ModelInfo>>,
    sessions: PersistedSessionViewModelRegistry,
    createRuntimeConfiguration: (AgentSettingsViewModel, CoroutineScope) -> RuntimeConfigurationViewModel,
    scope: CoroutineScope,
) : NewSessionViewModel {
    public val draft: NewSessionDraftPort
    override val models: StateFlow<List<ModelInfo>>
    override val settings: StateFlow<KodexAgentSettings>
    override val runtimeConfiguration: RuntimeConfigurationViewModel
    override val composer: ComposerViewModel

    override suspend fun updateModel(model: OpenAiModelId): Unit
    override suspend fun updateWorkingDirectory(workingDirectory: Path): Unit
    override suspend fun updateReasoningEffort(reasoningEffort: ReasoningEffort): Unit
    override suspend fun updateServiceTier(serviceTier: ServiceTier): Unit
    override suspend fun updateRequestUserInputMode(mode: RequestUserInputMode): Unit
    override suspend fun updateModelConfiguration(
        model: OpenAiModelId,
        reasoningEffort: ReasoningEffort,
        serviceTier: ServiceTier,
    ): Unit
    override suspend fun submit(expectedRevision: Long): PersistedSessionViewModel?
    override fun close(): Unit
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
