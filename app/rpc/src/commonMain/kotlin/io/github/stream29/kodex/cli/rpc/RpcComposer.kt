package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.app.agent.contract.ComposerDependencies
import io.github.stream29.kodex.app.agent.contract.ComposerFailureReporter
import io.github.stream29.kodex.app.agent.contract.ComposerOwnerId
import io.github.stream29.kodex.app.agent.contract.ComposerRequestInputPort
import io.github.stream29.kodex.app.agent.contract.ComposerRequestInputPresentation
import io.github.stream29.kodex.app.agent.contract.ComposerRuntimePort
import io.github.stream29.kodex.app.agent.contract.ComposerSubmitPort
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.cli.agent.createComposerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal fun createRpcComposerViewModel(
    binding: RpcSessionBinding,
    ownerScope: CoroutineScope,
    failureReporter: ComposerFailureReporter,
): ComposerViewModel {
    val presentation = binding.pendingAskUser.map { pending ->
        pending?.let {
            ComposerRequestInputPresentation.Pending(
                title = "Request user input",
                questionCount = it.questions.questions.size,
            )
        } ?: ComposerRequestInputPresentation.None
    }.stateIn(ownerScope, SharingStarted.Eagerly, ComposerRequestInputPresentation.None)

    return createComposerViewModel(
        ownerId = ComposerOwnerId("agent-${binding.agentId}"),
        dependencies = object : ComposerDependencies {
            override val runtime = object : ComposerRuntimePort {
                override val running: StateFlow<Boolean> = binding.running
                override val pendingSteer: StateFlow<List<AgentCommit.Steerable>> = binding.pendingSteer
            }

            override val submit = ComposerSubmitPort { _, content ->
                binding.submit(content)
            }

            override val requestInput = object : ComposerRequestInputPort {
                override val presentation: StateFlow<ComposerRequestInputPresentation> = presentation
            }

            override val failures = failureReporter
        },
        ownerScope = ownerScope,
    )
}
