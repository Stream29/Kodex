@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexRootSessionRepository
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.contract.ObservableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.agentstorage.contract.getSettingsAt
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.cli.sessiontitle.SessionTitleGenerator
import io.github.stream29.kodex.cli.sessiontitle.attachSessionTitleGeneration
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.openai.Reasoning
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import io.github.stream29.kodex.utils.rpcexception.SessionNotActive
import io.github.stream29.kodex.utils.rpcexception.SessionNotFound
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

public actual class BackendSessionManagement actual constructor(
    private val repository: KodexRootSessionRepository,
    private val newSessionDefaults: () -> KodexNewSessionSettings,
    private val titleSettings: () -> SessionTitleSettings,
    private val titleGenerator: SessionTitleGenerator,
    private val reportBackgroundFailure: (Throwable) -> Unit,
    private val checkAdmission: () -> Unit,
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

    public actual suspend fun getSessionCatalog(includeArchived: Boolean): List<SessionCatalogEntry> = inBackend {
        commands.withLock {
            repository.listEntries(includeArchived).map { entry ->
                SessionCatalogEntry(
                    sessionIndex = entry.entryIndex,
                    threadName = entry.threadName,
                    createdAt = entry.createdAt,
                    updatedAt = entry.lastActivityAt,
                    archived = entry.archived,
                    running = entry.running,
                    isActive = entry.isActive,
                )
            }.sortedWith(compareByDescending<SessionCatalogEntry> { it.updatedAt }.thenByDescending { it.sessionIndex })
        }
    }

    public actual suspend fun createSession(cwd: Path): Int = inBackend {
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

    public actual suspend fun keepSessionAlive(sessionIndex: Int): Unit = inBackend {
        commands.withLock {
            requireEntry(sessionIndex)
            repository.open(sessionIndex).let {}
        }
    }

    public actual suspend fun archiveSession(sessionIndex: Int): Unit = inBackend {
        commands.withLock {
            requireEntry(sessionIndex)
            repository.getEntry(sessionIndex).archive()
        }
    }

    public actual suspend fun unarchiveSession(sessionIndex: Int): Unit = inBackend {
        commands.withLock {
            requireEntry(sessionIndex)
            repository.getEntry(sessionIndex).unarchive()
        }
    }

    public actual suspend fun deleteSession(sessionIndex: Int): Boolean = inBackend {
        commands.withLock {
            if (sessionIndex !in repository.list()) return@withLock false
            repository.delete(sessionIndex)
            true
        }
    }

    public actual suspend fun forkSession(sessionIndex: Int): Int = fork(sessionIndex, null, null)

    public actual suspend fun forkSessionHistory(
        sessionIndex: Int,
        untilExclusive: Int,
        expectedCacheNonce: Long,
    ): Int = fork(sessionIndex, untilExclusive, expectedCacheNonce)

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun fork(sourceIndex: Int, boundary: Int?, nonce: Long?): Int = inBackend {
        commands.withLock {
            withManagementSource(sourceIndex, requireActive = nonce != null) { source ->
                require(source.runtime.runningTurn.value == null) { "Cannot fork a running Agent." }
                source.runtime.modify { sourceStorage ->
                    require(source.runtime.runningTurn.value == null) { "Cannot fork a running Agent." }
                    if (nonce != null &&
                        (source.runtime.storage as ObservableKodexAgentStorage).cacheNonce.value != nonce
                    ) throw CacheNonceMismatch()
                    val latest = sourceStorage.latestIndex()
                    require(latest >= 0) { "Cannot fork an uninitialized Session." }
                    val until = boundary ?: (latest + 1)
                    require(until > 0 && until <= latest + 1) { "Invalid fork boundary." }
                    val settings = sourceStorage.getSettingsAt(until - 1)
                    val targetIndex = repository.createFork(sourceIndex)
                    try {
                        val target = repository.open(targetIndex)
                        try {
                            target.runtime.modify { storage ->
                                if (boundary != null) storage.revert(until)
                                storage.commit(
                                    settings.copy(
                                        turnId = Uuid.generateV7().toString(),
                                        turnState = null,
                                        threadName = "[fork] ${settings.threadName.trim().ifEmpty { "Session $targetIndex" }}",
                                    ),
                                )
                            }
                        } finally {
                            withContext(NonCancellable) { repository.closeSession(targetIndex) }
                        }
                        targetIndex
                    } catch (failure: Throwable) {
                        withContext(NonCancellable) {
                            runCatching { repository.delete(targetIndex) }.onFailure(failure::addSuppressed)
                        }
                        throw failure
                    }
                }
            }
        }
    }

    internal suspend fun ensureSessionExists(sessionIndex: Int): Unit = inBackend {
        commands.withLock {
            requireEntry(sessionIndex)
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
            session.runtime.modify { it.initialize(initialSettings.copy(threadName = name(index))) }
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

    private suspend fun <T> withManagementSource(
        index: Int,
        requireActive: Boolean,
        block: suspend (KodexAgentSession) -> T,
    ): T {
        requireEntry(index)
        val active = repository.openSessions.value[index]?.takeIf { it.coroutineContext.job.isActive }
        if (requireActive && active == null) throw SessionNotActive()
        if (active != null) {
            return block(active)
        }
        val temporary = repository.open(index)
        return try {
            block(temporary)
        } finally {
            withContext(NonCancellable) { repository.closeSession(index) }
        }
    }

    private suspend fun requireEntry(index: Int) {
        if (index !in repository.list()) throw SessionNotFound()
    }
}
