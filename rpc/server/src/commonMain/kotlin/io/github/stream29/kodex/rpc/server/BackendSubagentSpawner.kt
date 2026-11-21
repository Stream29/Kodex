package io.github.stream29.kodex.rpc.server

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.agentcontext.prefix.subagentHierarchyInstructions
import io.github.stream29.kodex.agentruntime.contract.AgentResumeNotAdmittedException
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
import io.github.stream29.kodex.agentsession.contract.StoredAgent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSubagentCreatedEvent
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.agentstorage.contract.getSettingsAtOrNull
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.rpc.models.SubagentSpawnRejection
import io.github.stream29.kodex.rpc.models.SubagentSpawnRequest
import io.github.stream29.kodex.rpc.models.SubagentSpawnResult
import io.github.stream29.kodex.rpc.models.SubagentSpawnStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.job

internal class BackendSubagentSpawner(
    private val repository: KodexSessionRepository,
    private val models: () -> List<ModelInfo>,
    private val allocate: suspend (Int, KodexAgentSession) -> StoredAgent,
    private val discardUninitialized: suspend (Int) -> Boolean,
    private val record: suspend (KodexAgentSession, StableSubagentCreatedEvent) -> Unit,
    private val submit: suspend (KodexAgentSession, List<ContentItem>) -> Unit,
) {
    suspend fun spawn(parent: KodexAgentSession, request: SubagentSpawnRequest): SubagentSpawnResult {
        val parentId = request.source.agentId
        if (parent.runtime.agentId != parentId || parent.runtime.invocationId != request.source.invocationId ||
            !parent.coroutineContext.job.isActive
        ) {
            return SubagentSpawnResult.Rejected(SubagentSpawnRejection.InvalidSource)
        }
        if (request.message.isBlank()) return SubagentSpawnResult.Rejected(SubagentSpawnRejection.InvalidMessage)
        if (request.name.isBlank() || request.name.any { it.isISOControl() }) {
            return SubagentSpawnResult.Rejected(SubagentSpawnRejection.InvalidConfiguration)
        }
        val name = request.name.trim()
        val parentSettings = parent.storage.getSettingsAtOrNull(Int.MAX_VALUE)
            ?: return SubagentSpawnResult.Rejected(SubagentSpawnRejection.InvalidSource)
        val settings = when (val decision = decideSubagentCreation(
            parentSettings, request.model, request.reasoningEffort, request.workingDirectory, models,
        )) {
            is SubagentCreationDecision.Allowed -> decision.settings
            SubagentCreationDecision.Rejected.CreationRejected ->
                return SubagentSpawnResult.Rejected(SubagentSpawnRejection.CreationRejected)
            SubagentCreationDecision.Rejected.InvalidDirectory ->
                return SubagentSpawnResult.Rejected(SubagentSpawnRejection.InvalidDirectory)
            SubagentCreationDecision.Rejected.InvalidConfiguration ->
                return SubagentSpawnResult.Rejected(SubagentSpawnRejection.InvalidConfiguration)
        }

        var allocated: StoredAgent? = null
        val childOwner: KodexAgentSession
        val child: StoredAgent
        try {
            // Remember the allocation inside the closure even if the caller cancels on handoff.
            child = withContext(NonCancellable) { allocate(parentId, parent).also { allocated = it } }
            val instructions = subagentHierarchyInstructions(ancestry(child))
            childOwner = checkNotNull(repository.openAgent(child.agentId))
            childOwner.runtime.modify { it.initialize(settings.copy(instructions = instructions, name = name)) }
        } catch (_: AgentResumeNotAdmittedException) {
            check(allocated == null) { "Unexpected owner rejection after allocation." }
            return SubagentSpawnResult.Rejected(SubagentSpawnRejection.InvalidSource)
        } catch (failure: Throwable) {
            allocated?.let { known ->
                withContext(NonCancellable) {
                    runCatching { discardUninitialized(known.agentId) }.onFailure(failure::addSuppressed)
                }
            }
            throw failure
        }
        try {
            record(parent, StableSubagentCreatedEvent(child.agentId, name, request.message))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Logger.warn(failure) { "Child ${child.agentId} was initialized but its creation could not be recorded." }
            return SubagentSpawnResult.Partial(child.sessionId, parentId, child.agentId, SubagentSpawnStage.Record)
        }
        try {
            submit(childOwner, listOf(ContentItem.InputText(request.message)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Logger.warn(failure) { "Child ${child.agentId} was recorded but first input acceptance was not confirmed." }
            return SubagentSpawnResult.Partial(child.sessionId, parentId, child.agentId, SubagentSpawnStage.Submit)
        }
        return SubagentSpawnResult.Created(child.sessionId, parentId, child.agentId)
    }

    private suspend fun ancestry(child: StoredAgent): List<Int> {
        val childToRoot = mutableListOf(child.agentId)
        val visited = mutableSetOf(child.agentId)
        var parentId = child.parentAgentId
        while (parentId != null) {
            check(visited.add(parentId)) { "Cyclic Agent ancestry." }
            val parent = checkNotNull(repository.getAgentRecord(parentId))
            check(parent.sessionId == child.sessionId) { "Agent ancestry crossed a Session boundary." }
            childToRoot += parentId
            parentId = parent.parentAgentId
        }
        return childToRoot.asReversed()
    }

    private companion object {
        val Logger = KotlinLogging.logger("SubagentSpawn")
    }
}
