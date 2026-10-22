@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.newsession

import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModelArguments
import io.github.stream29.kodex.app.session.contract.NewSessionViewModelFactory
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModelRegistry
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel
import io.github.stream29.kodex.cli.rpc.RpcGlobalSettings
import io.github.stream29.kodex.cli.rpc.RpcSessionDraft
import io.github.stream29.kodex.cli.rpc.RpcSessionViews
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
    views: RpcSessionViews,
    sessions: PersistedSessionViewModelRegistry,
    global: RpcGlobalSettings,
    scope: CoroutineScope,
) : NewSessionViewModelFactory {
    override fun create(arguments: NewSessionViewModelArguments): NewSessionViewModel
}

/** Reusable frontend child; only cwd/Composer are local, model configuration edits shared defaults. */
public expect class RpcNewSessionViewModel internal constructor(
    arguments: NewSessionViewModelArguments,
    views: RpcSessionViews,
    sessions: PersistedSessionViewModelRegistry,
    global: RpcGlobalSettings,
    scope: CoroutineScope,
) : NewSessionViewModel {
    public val draft: RpcSessionDraft
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
