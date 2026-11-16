package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.app.agent.contract.ComposerDependencies
import io.github.stream29.kodex.app.agent.contract.ComposerFailureReporter
import io.github.stream29.kodex.app.agent.contract.ComposerOwnerId
import io.github.stream29.kodex.app.agent.contract.ComposerRequestInputPort
import io.github.stream29.kodex.app.agent.contract.ComposerRequestInputPresentation
import io.github.stream29.kodex.app.agent.contract.ComposerRuntimePort
import io.github.stream29.kodex.app.agent.contract.ComposerSubmitPort
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.app.agent.contract.ComposerCancellationPort
import io.github.stream29.kodex.cli.agent.createComposerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted

public actual fun createRpcComposerViewModel(
    binding: RpcSessionBinding,
    ownerScope: CoroutineScope,
    cancellationPort: ComposerCancellationPort,
    failureReporter: ComposerFailureReporter,
): ComposerViewModel {
    val presentation = combine(binding.pendingAskUser, binding.settings) { pending, settings ->
        pending?.let {
            ComposerRequestInputPresentation.Pending(
                callId = "ask-${it.askId}",
                mode = settings.requestUserInputMode,
                title = "Request user input",
                questionCount = it.questions.questions.size,
            )
        } ?: ComposerRequestInputPresentation.None
    }.stateIn(ownerScope, SharingStarted.Eagerly, ComposerRequestInputPresentation.None)

    return createComposerViewModel(
        ownerId = ComposerOwnerId("session-${binding.sessionIndex}"),
        dependencies = object : ComposerDependencies {
            override val runtime = object : ComposerRuntimePort {
                override val running: StateFlow<Boolean> = binding.running
                override val pendingSteer: StateFlow<List<StableIndexEvent.Steerable>> = binding.pendingSteer
            }

            override val submit = ComposerSubmitPort { _, content ->
                binding.submit(content)
            }

            override val cancellation = cancellationPort

            override val requestInput = object : ComposerRequestInputPort {
                override val presentation: StateFlow<ComposerRequestInputPresentation> = presentation
            }

            override val failures = failureReporter
        },
        ownerScope = ownerScope,
    )
}
