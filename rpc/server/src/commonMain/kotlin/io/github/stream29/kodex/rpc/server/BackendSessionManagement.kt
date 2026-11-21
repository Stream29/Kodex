package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.agentsession.contract.AgentForkCopy
import io.github.stream29.kodex.agentsession.contract.AgentForkSourceChanged
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
import io.github.stream29.kodex.agentsession.contract.RunningSubagentsPreventDeletion
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.agentstorage.contract.getSettingsAtOrNull
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.cli.sessiontitle.SessionTitleGenerator
import io.github.stream29.kodex.cli.sessiontitle.attachSessionTitleGeneration
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.openai.Reasoning
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.rpc.models.SessionSpawnRequest
import io.github.stream29.kodex.rpc.models.SessionSpawnResult
import io.github.stream29.kodex.rpc.models.SessionSpawnRejection
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import io.github.stream29.kodex.utils.rpcexception.AgentNotFound
import io.github.stream29.kodex.utils.rpcexception.AgentNotActive
import io.github.stream29.kodex.utils.rpcexception.SessionNotFound
import io.github.stream29.kodex.utils.rpcexception.SessionHasRunningSubagents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal class BackendSessionManagement(
    private val repository: KodexSessionRepository,
    private val newSessionDefaults: () -> KodexNewSessionSettings,
    private val titleSettings: () -> SessionTitleSettings,
    private val titleGenerator: SessionTitleGenerator,
    private val checkAdmission: () -> Unit = {},
) {
    private val operations: CoroutineScope = repository.supervisorChildScope()
    private val commands = Mutex()

    private suspend fun <T> inBackend(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        repository.ensureActive()
        checkAdmission()
        return operations.async {
            repository.ensureActive()
            checkAdmission()
            block()
        }.await()
    }

    suspend fun getSessionCatalog(includeArchived: Boolean): List<SessionCatalogEntry> = inBackend {
        commands.withLock {
            repository.listEntries(includeArchived).map { it.toCatalogEntry() }
                .sortedWith(compareByDescending<SessionCatalogEntry> { it.updatedAt }.thenByDescending { it.sessionIndex })
        }
    }

    suspend fun createSession(cwd: Path): Int = inBackend {
        commands.withLock {
            val defaults = newSessionDefaults()
            val initialSettings = KodexAgentSettings(
                model = defaults.model,
                reasoning = Reasoning(effort = defaults.reasoningEffort),
                serviceTier = defaults.serviceTier,
                requestUserInputMode = defaults.requestUserInputMode,
                cwd = cwd,
            )
            create(initialSettings, attachAutoTitle = true) { "Session $it" }.first
        }
    }

    suspend fun spawnSession(request: SessionSpawnRequest, models: () -> List<ModelInfo>): SessionSpawnResult = inBackend {
        commands.withLock {
            val inherited = request.source?.let { source ->
                val owner = repository.openSessions.value[source.agentId]
                if (owner == null || !owner.coroutineContext.job.isActive || owner.runtime.invocationId != source.invocationId) {
                    return@withLock SessionSpawnResult.Rejected(SessionSpawnRejection.InvalidSource)
                }
                owner.storage.getSettingsAtOrNull(Int.MAX_VALUE)
                    ?: return@withLock SessionSpawnResult.Rejected(SessionSpawnRejection.InvalidSource)
            }
            val rawDirectory = request.workingDirectory ?: inherited?.cwd?.toString() ?: request.launchDirectory
            val cwd = resolveSpawnDirectory(rawDirectory)
                ?: return@withLock SessionSpawnResult.Rejected(SessionSpawnRejection.InvalidDirectory)
            val defaults = newSessionDefaults()
            val model = request.model ?: inherited?.model ?: defaults.model
            val effort = request.reasoningEffort ?: inherited?.reasoning?.effort ?: defaults.reasoningEffort
            val tier = request.serviceTier ?: inherited?.serviceTier ?: defaults.serviceTier
            if (!supportsSpawnConfiguration(models(), model, request.model != null, effort, tier)) {
                return@withLock SessionSpawnResult.Rejected(SessionSpawnRejection.InvalidConfiguration)
            }
            val initial = KodexAgentSettings(
                model = model, reasoning = Reasoning(effort = effort), serviceTier = tier, cwd = cwd,
                requestUserInputMode = request.requestUserInputMode ?: inherited?.requestUserInputMode ?: defaults.requestUserInputMode,
            )
            val (id, created) = create(initial, attachAutoTitle = true) { "Session $it" }
            SessionSpawnResult.Created(id, checkNotNull(created.runtime.agentId), created.runtime.invocationId)
        }
    }

    suspend fun getRootAgentId(sessionIndex: Int): Int = inBackend {
        commands.withLock {
            try {
                repository.getRootAgentId(sessionIndex)
            } catch (error: IllegalArgumentException) {
                if (sessionIndex !in repository.list()) throw SessionNotFound()
                throw error
            }
        }
    }

    suspend fun archiveSession(sessionIndex: Int): Unit = inBackend {
        commands.withLock {
            requireEntry(sessionIndex)
            repository.getEntry(sessionIndex).archive()
        }
    }

    suspend fun unarchiveSession(sessionIndex: Int): Unit = inBackend {
        commands.withLock {
            requireEntry(sessionIndex)
            repository.getEntry(sessionIndex).unarchive()
        }
    }

    suspend fun deleteSession(sessionIndex: Int): Boolean = inBackend {
        commands.withLock {
            if (sessionIndex !in repository.list()) return@withLock false
            try {
                repository.delete(sessionIndex)
            } catch (_: RunningSubagentsPreventDeletion) {
                throw SessionHasRunningSubagents()
            }
            true
        }
    }

    suspend fun forkAgent(agentId: Int): Int = fork(agentId, null, null)

    suspend fun forkAgentHistory(
        agentId: Int,
        untilExclusive: Int,
        expectedCacheNonce: Long,
    ): Int = fork(agentId, untilExclusive, expectedCacheNonce)

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun fork(agentId: Int, boundary: Int?, nonce: Long?): Int = inBackend {
        commands.withLock {
            if (repository.getAgentRecord(agentId) == null) throw AgentNotFound()
            val source = repository.openSessions.value[agentId]?.takeIf { it.coroutineContext.job.isActive }
            if (nonce != null && source == null) throw AgentNotActive()
            var copy: AgentForkCopy? = null
            suspend fun capture() {
                currentCoroutineContext().ensureActive()
                // Keep the allocation receipt even if backend cancellation arrives during the copy.
                withContext(NonCancellable) {
                    copy = repository.createAgentFork(agentId, source, boundary) ?: throw AgentNotFound()
                }
            }
            try {
                if (source == null) {
                    capture()
                } else {
                    require(source.runtime.runningTurn.value == null) { "Cannot fork a running Agent." }
                    source.runtime.modify {
                        if (nonce != null && source.storage.cacheNonce.value != nonce) throw CacheNonceMismatch()
                        capture()
                    }
                }
                val (targetIndex, settings) = checkNotNull(copy)
                val target = repository.open(targetIndex)
                try {
                    target.runtime.modify { storage ->
                        storage.commit(
                            settings.copy(
                                turnId = Uuid.generateV7().toString(),
                                turnState = null,
                                name = "[fork] ${settings.name.trim().ifEmpty { "Session $targetIndex" }}",
                            ),
                        )
                    }
                } finally {
                    withContext(NonCancellable) { repository.closeSession(targetIndex) }
                }
                targetIndex
            } catch (_: AgentForkSourceChanged) {
                throw AgentNotActive()
            } catch (failure: Throwable) {
                copy?.let { (targetIndex, _) ->
                    withContext(NonCancellable) {
                        runCatching { repository.delete(targetIndex) }.onFailure(failure::addSuppressed)
                    }
                }
                throw failure
            }
        }
    }

    internal suspend fun <T> withPersistedAgent(agentId: Int, action: suspend () -> T): T = inBackend {
        commands.withLock {
            repository.getAgentRecord(agentId) ?: throw AgentNotFound()
            action()
        }
    }

    private suspend fun create(
        initialSettings: KodexAgentSettings,
        attachAutoTitle: Boolean,
        name: (Int) -> String,
    ): Pair<Int, KodexAgentSession> {
        val index = repository.create()
        try {
            val session = repository.open(index)
            session.runtime.modify { it.initialize(initialSettings.copy(name = name(index))) }
            if (attachAutoTitle) {
                attachSessionTitleGeneration(
                    scope = session.runtime,
                    agentState = session.runtime,
                    settings = titleSettings,
                    generator = titleGenerator,
                )
            }
            return index to session
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                runCatching { repository.delete(index) }.onFailure(failure::addSuppressed)
            }
            throw failure
        }
    }

    private suspend fun requireEntry(index: Int) {
        if (index !in repository.list()) throw SessionNotFound()
    }
}
