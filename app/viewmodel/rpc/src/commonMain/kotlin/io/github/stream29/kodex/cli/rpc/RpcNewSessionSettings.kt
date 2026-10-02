package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.app.settings.SettingsUpdateQueue
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.app.settings.createNewSessionDefaultsViewModel
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/**
 * Host composition of a real defaults component and its independent application-scope write queue.
 * All projection, revisions and command admission belong to the component; this adapter owns only
 * field-specific backend writes and queue disposal. Closing the child cannot cancel accepted writes.
 */
public class RpcNewSessionSettings private constructor(
    private val resources: DefaultsResources,
) : NewSessionSettingsViewModel by resources.child {
    public constructor(global: RpcGlobalSettings, applicationScope: CoroutineScope) :
        this(DefaultsResources(global, applicationScope))

    override fun close() {
        resources.child.close()
        resources.dependencies.close()
    }
}

private class DefaultsResources(global: RpcGlobalSettings, scope: CoroutineScope) {
    val dependencies = RpcNewSessionDefaultsDependencies(global, scope)
    val child = createNewSessionDefaultsViewModel(dependencies, scope)
}

private class RpcNewSessionDefaultsDependencies(
    private val global: RpcGlobalSettings,
    applicationScope: CoroutineScope,
) : NewSessionDefaultsDependencies, AutoCloseable {
    private val updates = SettingsUpdateQueue(applicationScope)
    private var closed = false
    override val defaults = global.settings.projectState { it.newSession }
    override val models = global.models
    override val operationFailure = global.operationFailure

    private fun <F> admit(
        expected: F,
        select: (KodexNewSessionSettings) -> F,
        replace: (KodexNewSessionSettings) -> KodexNewSessionSettings,
    ): NewSessionDefaultsAdmission {
        if (closed) return NewSessionDefaultsAdmission.Rejected
        updates.submit {
            try {
                global.settings.editField(expected, { select(it.newSession) },
                    { it.copy(newSession = replace(it.newSession)) }, global::ensureActive)
                global.dismissOperationFailure()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { global.reportOperationFailure(failure) }
        }
        return NewSessionDefaultsAdmission.Accepted
    }
    override fun admitModel(expected: OpenAiModelId, requested: OpenAiModelId) =
        admit(expected, { it.model }, { it.copy(model = requested) })
    override fun admitReasoningEffort(expected: ReasoningEffort, requested: ReasoningEffort) =
        admit(expected, { it.reasoningEffort }, { it.copy(reasoningEffort = requested) })
    override fun admitServiceTier(expected: ServiceTier, requested: ServiceTier) =
        admit(expected, { it.serviceTier }, { it.copy(serviceTier = requested) })
    override fun admitRequestUserInputMode(expected: RequestUserInputMode, requested: RequestUserInputMode) =
        admit(expected, { it.requestUserInputMode }, { it.copy(requestUserInputMode = requested) })
    override fun reportFailure(failure: Throwable) { global.reportOperationFailure(failure) }
    override fun dismissFailure() { global.dismissOperationFailure() }
    override fun close() { closed = true; updates.close() }
}
