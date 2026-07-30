package io.github.stream29.codex.lite.cli.session

import io.github.stream29.codex.lite.agentsession.contract.CodexAgentSession
import io.github.stream29.codex.lite.cli.agent.AgentAutomaticTitleConfiguration
import io.github.stream29.codex.lite.cli.agent.AgentRuntimeViewModel
import io.github.stream29.codex.lite.cli.agent.AgentRuntimeViewModel as createAgentRuntimeViewModel
import io.github.stream29.codex.lite.cli.history.AgentHistoryViewModel
import io.github.stream29.codex.lite.cli.history.AgentHistoryViewModel as createAgentHistoryViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One root session tree projected for the CLI. */
public data class RootSessionViewState(
    public val rootAgentId: String,
    public val agents: List<AgentRuntimeTreeEntry> = emptyList(),
    public val selectedAgentId: String? = null,
    /** Changes whenever a child Agent VM publishes, so tree renderers can observe nested state. */
    public val renderRevision: Long = 0,
)

/** A tree node owns a reusable VM for exactly one open Agent runtime. */
public data class AgentRuntimeTreeEntry(
    public val agentId: String,
    public val parentAgentId: String?,
    public val depth: Int,
    public val viewModel: AgentRuntimeViewModel,
    /** Incremental clean timeline frontend for this exact Agent runtime. */
    public val historyViewModel: AgentHistoryViewModel,
    public val selected: Boolean,
)

/**
 * CLI owner for one root [CodexAgentSession] and its currently discovered descendants.
 *
 * [refresh] reads direct repository snapshots and observes each discovered Agent's child snapshot,
 * so the tree stays synchronized when the multi-agent runtime creates or deletes descendants.
 */
public class RootSessionViewModel internal constructor(
    public val rootSession: CodexAgentSession,
    private val sessionScope: CoroutineScope,
    private val automaticTitleConfiguration: AgentAutomaticTitleConfiguration? = null,
) : AutoCloseable {
    private val agentViewModels = linkedMapOf<String, AgentRuntimeViewModel>()
    private val agentHistoryViewModels = linkedMapOf<String, AgentHistoryViewModel>()
    private val agentObservations = linkedMapOf<String, Job>()
    private val topologyObservations = linkedMapOf<String, Job>()
    private val refreshMutex = Mutex()
    private val mutableState = MutableStateFlow(
        RootSessionViewState(rootAgentId = rootSession.storage.id),
    )

    public val state: StateFlow<RootSessionViewState> = mutableState.asStateFlow()

    /** Opens currently persisted descendants and preserves VMs for unchanged Agent identities. */
    public suspend fun refresh(): Unit = refreshMutex.withLock {
        val discoveredAgents = mutableListOf<DiscoveredAgent>()
        suspend fun visit(
            session: CodexAgentSession,
            parentAgentId: String?,
            depth: Int,
        ) {
            val agentId = session.storage.id
            val childEntries = session.subagents.entries.value
            discoveredAgents += DiscoveredAgent(
                agentId = agentId,
                parentAgentId = parentAgentId,
                depth = depth,
                session = session,
                childEntries = childEntries,
            )
            childEntries.forEach { entryIndex ->
                visit(
                    session = session.subagents.open(entryIndex),
                    parentAgentId = agentId,
                    depth = depth + 1,
                )
            }
        }

        visit(rootSession, parentAgentId = null, depth = 0)
        val discoveredIds = discoveredAgents.mapTo(mutableSetOf(), DiscoveredAgent::agentId)
        agentViewModels.entries.removeAll { (agentId, _) ->
            if (agentId in discoveredIds) {
                false
            } else {
                agentHistoryViewModels.remove(agentId)
                agentObservations.remove(agentId)
                true
            }
        }
        topologyObservations.entries.removeAll { (agentId, _) ->
            if (agentId in discoveredIds) {
                false
            } else {
                true
            }
        }
        discoveredAgents.forEach { discovered ->
            val viewModel = agentViewModels.getOrPut(discovered.agentId) {
                discovered.session.createAgentRuntimeViewModel(
                    session = discovered.session,
                    automaticTitleConfiguration = automaticTitleConfiguration
                        ?.takeIf { discovered.agentId == rootSession.storage.id },
                )
            }
            agentHistoryViewModels.getOrPut(discovered.agentId) {
                createAgentHistoryViewModel(discovered.session.runtime)
            }
            if (discovered.agentId !in agentObservations) {
                agentObservations[discovered.agentId] = discovered.session.launch {
                    viewModel.state.collect {
                        mutableState.update { current ->
                            current.copy(renderRevision = current.renderRevision + 1)
                        }
                    }
                }
            }
            if (discovered.agentId !in topologyObservations) {
                topologyObservations[discovered.agentId] = discovered.session.launch {
                    var observedEntries = discovered.childEntries
                    discovered.session.subagents.entries.collect { currentEntries ->
                        if (currentEntries != observedEntries) {
                            observedEntries = currentEntries
                            refresh()
                        }
                    }
                }
            }
        }
        val selected = mutableState.value.selectedAgentId?.takeIf { it in discoveredIds }
            ?: rootSession.storage.id
        mutableState.value = RootSessionViewState(
            rootAgentId = rootSession.storage.id,
            agents = discoveredAgents.map { discovered ->
                AgentRuntimeTreeEntry(
                    agentId = discovered.agentId,
                    parentAgentId = discovered.parentAgentId,
                    depth = discovered.depth,
                    viewModel = agentViewModels.getValue(discovered.agentId),
                    historyViewModel = agentHistoryViewModels.getValue(discovered.agentId),
                    selected = discovered.agentId == selected,
                )
            },
            selectedAgentId = selected,
            renderRevision = mutableState.value.renderRevision + 1,
        )
    }

    public fun selectAgent(agentId: String) {
        val current = mutableState.value
        require(current.agents.any { entry -> entry.agentId == agentId }) {
            "Agent $agentId does not belong to this root session."
        }
        mutableState.value = current.copy(
            agents = current.agents.map { entry -> entry.copy(selected = entry.agentId == agentId) },
            selectedAgentId = agentId,
        )
    }

    override fun close() {
        sessionScope.cancel()
    }
}

/** Creates a root-session VM whose lifecycle follows its root session scope. */
public fun CoroutineScope.RootSessionViewModel(
    rootSession: CodexAgentSession,
    automaticTitleConfiguration: AgentAutomaticTitleConfiguration? = null,
): RootSessionViewModel =
    RootSessionViewModel(
        rootSession = rootSession,
        sessionScope = rootSession,
        automaticTitleConfiguration = automaticTitleConfiguration,
    )

private data class DiscoveredAgent(
    val agentId: String,
    val parentAgentId: String?,
    val depth: Int,
    val session: CodexAgentSession,
    val childEntries: List<Int>,
)
