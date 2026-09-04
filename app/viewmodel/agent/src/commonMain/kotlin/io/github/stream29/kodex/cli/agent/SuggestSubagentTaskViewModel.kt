package io.github.stream29.kodex.cli.agent

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableSuggestSubagentTaskResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.agentruntime.contract.AgentRuntime
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskState
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskSubmissionResult
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskViewModel
import io.github.stream29.kodex.app.agent.contract.SuggestedSessionConfiguration
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class SuggestSubagentTaskViewModelImpl(
    private val runtime: AgentRuntime,
    private val ownerScope: CoroutineScope,
    private val createSessions: (suspend (
        io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskArgs,
        SuggestedSessionConfiguration,
    ) -> List<io.github.stream29.kodex.tool.multiagent.SuggestedSessionMeta>)?,
    private val resumeRuntime: () -> Unit,
    private val defaultConfiguration: () -> SuggestedSessionConfiguration,
) : SuggestSubagentTaskViewModel {
    private val mutableState = MutableStateFlow<SuggestSubagentTaskState>(SuggestSubagentTaskState.Idle)
    private var pendingEvent: PendingSuggestSubagentTaskToolEvent? = null
    private var closed = false

    override val state: StateFlow<SuggestSubagentTaskState> = mutableState.asStateFlow()

    override fun updateFeedback(callId: String, text: String): Boolean =
        update(callId) { current ->
            current.copy(feedback = text, revision = current.revision + 1)
        }

    override fun updateConfiguration(
        callId: String,
        configuration: SuggestedSessionConfiguration,
    ): Boolean = update(callId) { current ->
        current.copy(configuration = configuration, revision = current.revision + 1)
    }

    override suspend fun submit(
        callId: String,
        expectedRevision: Long,
        accepted: Boolean,
    ): SuggestSubagentTaskSubmissionResult = ownerScope.async(start = CoroutineStart.UNDISPATCHED) {
        if (closed) return@async SuggestSubagentTaskSubmissionResult.Stale
        val event = pendingEvent?.takeIf { it.callId == callId }
            ?: return@async SuggestSubagentTaskSubmissionResult.Stale
        val current = mutableState.value as? SuggestSubagentTaskState.Pending
            ?: return@async SuggestSubagentTaskSubmissionResult.Stale
        if (current.callId != callId || current.revision != expectedRevision) {
            return@async SuggestSubagentTaskSubmissionResult.Stale
        }
        if (current.submitting) return@async SuggestSubagentTaskSubmissionResult.Busy
        val submitting = current.copy(submitting = true, revision = current.revision + 1)
        if (!mutableState.compareAndSet(current, submitting)) {
            return@async SuggestSubagentTaskSubmissionResult.Stale
        }
        try {
            val feedback = submitting.feedback.takeIf { it.isNotBlank() }
            val response = if (!accepted) {
                SuggestSubagentTaskResponse.Rejected(feedback = feedback)
            } else {
                val dispatcher = createSessions
                    ?: error("Subagent Session creation is not connected.")
                SuggestSubagentTaskResponse.Accepted(
                    feedback = feedback,
                    sessions = dispatcher(event.arguments, submitting.configuration),
                )
            }
            runtime.completeToolCall(
                StableSuggestSubagentTaskToolEvent(
                    callId = event.callId,
                    itemId = event.itemId,
                    arguments = event.arguments,
                    result = StableSuggestSubagentTaskResult.Completed(response),
                ),
            )
            pendingEvent = null
            mutableState.compareAndSet(submitting, SuggestSubagentTaskState.Idle)
            resumeRuntime()
            SuggestSubagentTaskSubmissionResult.Submitted
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            val message = failure.message ?: failure.toString()
            mutableState.compareAndSet(
                submitting,
                submitting.copy(submitting = false, revision = submitting.revision + 1),
            )
            SuggestSubagentTaskSubmissionResult.Failed(message)
        }
    }.await()

    internal fun synchronize(pending: PendingSuggestSubagentTaskToolEvent?) {
        if (closed || pendingEvent?.callId == pending?.callId) return
        pendingEvent = pending
        mutableState.value = pending?.let {
            SuggestSubagentTaskState.Pending(
                callId = it.callId,
                arguments = it.arguments,
                configuration = defaultConfiguration(),
            )
        } ?: SuggestSubagentTaskState.Idle
    }

    override fun close() {
        if (closed) return
        closed = true
        pendingEvent = null
        mutableState.value = SuggestSubagentTaskState.Idle
    }

    private fun update(
        callId: String,
        transform: (SuggestSubagentTaskState.Pending) -> SuggestSubagentTaskState.Pending,
    ): Boolean {
        if (closed) return false
        while (true) {
            val current = mutableState.value as? SuggestSubagentTaskState.Pending ?: return false
            if (current.callId != callId || current.submitting) return false
            val updated = transform(current)
            if (mutableState.compareAndSet(current, updated)) return true
        }
    }
}
