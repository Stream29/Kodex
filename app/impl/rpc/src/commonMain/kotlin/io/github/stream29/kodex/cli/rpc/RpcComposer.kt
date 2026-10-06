package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.kodex.app.agent.contract.ComposerDependencies
import io.github.stream29.kodex.app.agent.contract.ComposerFailureReporter
import io.github.stream29.kodex.app.agent.contract.ComposerOwnerId
import io.github.stream29.kodex.app.agent.contract.ComposerRequestInputPort
import io.github.stream29.kodex.app.agent.contract.ComposerRequestInputPresentation
import io.github.stream29.kodex.app.agent.contract.ComposerResumePort
import io.github.stream29.kodex.app.agent.contract.ComposerRuntimePort
import io.github.stream29.kodex.app.agent.contract.ComposerSteerPort
import io.github.stream29.kodex.app.agent.contract.ComposerSubmitPort
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.app.agent.contract.ComposerCancellationPort
import io.github.stream29.kodex.cli.agent.createComposerViewModel
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.rpc.client.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted

/**
 * Creates the Composer child bound to one exact RPC Session binding.
 * Command callbacks must belong to that binding's presentation owner: resume/cancel admit
 * work through its caught command boundary, and failures use its existing Agent failure sink.
 * No callback may resolve the currently selected Session or silently discard a failure.
 */
public fun createRpcComposerViewModel(
    binding: RpcSessionBinding,
    ownerScope: CoroutineScope,
    resumePort: ComposerResumePort,
    cancellationPort: ComposerCancellationPort,
    failureReporter: ComposerFailureReporter,
): ComposerViewModel {
    val presentation = combine(binding.state, binding.settings) { state, settings ->
        val pending = (state as? AgentStateValue.ToolPending)
            ?.events
            ?.filterIsInstance<PendingRequestUserInputToolEvent>()
            ?.firstOrNull()
        pending?.let {
            ComposerRequestInputPresentation.Pending(
                callId = it.callId,
                mode = settings.requestUserInputMode,
                title = "Request user input",
                questionCount = it.arguments.questions.size,
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
                binding.appendUserMessage(content)
            }

            override val steer = ComposerSteerPort { _, content ->
                binding.pendingSteer.update { it + StableUserMessage(content) }
            }

            override val cancellation = cancellationPort
            override val resume = resumePort

            override val requestInput = object : ComposerRequestInputPort {
                override val presentation: StateFlow<ComposerRequestInputPresentation> = presentation
            }

            override val failures = failureReporter
        },
        ownerScope = ownerScope,
    )
}
