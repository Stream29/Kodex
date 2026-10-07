package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.agentstorage.contract.ObservableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.agentstorage.contract.revert
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.rpc.models.CreatedSuggestedSession
import io.github.stream29.kodex.tool.multiagent.SuggestedSessionMeta
import io.github.stream29.kodex.tool.multiagent.SuggestedSubagentTask
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import io.github.stream29.kodex.utils.rpcexception.SessionNotFound
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Management values from the same backend repository as live runtime services. */
public class BackendSessionManagement(
    private val host: BackendSessionHost,
    private val runtime: BackendAgentRuntimeRpc,
    private val reportBackgroundFailure: (Throwable) -> Unit,
) {
    private val commands = Mutex()

    public suspend fun getSessionCatalog(includeArchived: Boolean): List<SessionCatalogEntry> = host.inBackend {
        commands.withLock {
            host.repository().listEntries(includeArchived).map { entry ->
                SessionCatalogEntry(
                    sessionIndex = entry.entryIndex,
                    threadName = entry.threadName,
                    createdAt = host.repository().readCreatedAt(entry.entryIndex),
                    updatedAt = entry.lastActivityAt,
                    archived = entry.archived,
                    running = entry.running,
                    isActive = entry.isActive,
                )
            }.sortedWith(compareByDescending<SessionCatalogEntry> { it.updatedAt }.thenByDescending { it.sessionIndex })
        }
    }

    public suspend fun createSession(initialSettings: KodexAgentSettings): Int = host.inBackend {
        commands.withLock { create(initialSettings) { "Session $it" } }
    }

    public suspend fun keepSessionAlive(sessionIndex: Int): Unit = host.keepSessionAlive(sessionIndex)

    public suspend fun archiveSession(sessionIndex: Int): Unit = host.inBackend {
        commands.withLock {
            requireEntry(sessionIndex)
            host.repository().getEntry(sessionIndex).archive()
        }
    }

    public suspend fun unarchiveSession(sessionIndex: Int): Unit = host.inBackend {
        commands.withLock {
            requireEntry(sessionIndex)
            host.repository().getEntry(sessionIndex).unarchive()
        }
    }

    public suspend fun deleteSession(sessionIndex: Int): Boolean = host.inBackend {
        commands.withLock { host.deleteSession(sessionIndex) }
    }

    public suspend fun forkSession(sessionIndex: Int): Int = fork(sessionIndex, null, null)

    public suspend fun forkSessionHistory(
        sessionIndex: Int, untilExclusive: Int, expectedCacheNonce: Long,
    ): Int = fork(sessionIndex, untilExclusive, expectedCacheNonce)

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun fork(sourceIndex: Int, boundary: Int?, nonce: Long?): Int = host.inBackend {
        commands.withLock {
            host.withManagementSource(sourceIndex, requireActive = nonce != null) { source ->
                require(source.runtime.runningTurn.value == null) { "Cannot fork a running Agent." }
                source.runtime.modify { sourceStorage ->
                    require(source.runtime.runningTurn.value == null) { "Cannot fork a running Agent." }
                    if (nonce != null &&
                        (source.runtime.storage as ObservableKodexAgentStorage).index.cacheNonce.value != nonce
                    ) throw CacheNonceMismatch()
                    val latest = sourceStorage.latestIndex()
                    require(latest >= 0) { "Cannot fork an uninitialized Session." }
                    val until = boundary ?: (latest + 1)
                    require(until > 0 && until <= latest + 1) { "Invalid fork boundary." }
                    val settings = sourceStorage.settings[until - 1]
                    val targetIndex = host.repository().createFork(sourceIndex)
                    try {
                        val target = host.repository().open(targetIndex)
                        try {
                            target.runtime.modify { storage ->
                                if (boundary != null) storage.revert(until)
                                storage.settings[storage.latestIndex() + 1] = settings.copy(
                                    turnId = Uuid.generateV7().toString(),
                                    turnState = null,
                                    threadName = "[fork] ${settings.threadName.trim().ifEmpty { "Session $targetIndex" }}",
                                )
                            }
                        } finally {
                            withContext(NonCancellable) { target.cancelAndJoin() }
                        }
                        targetIndex
                    } catch (failure: Throwable) {
                        withContext(NonCancellable) {
                            runCatching { host.repository().delete(targetIndex) }.onFailure(failure::addSuppressed)
                        }
                        throw failure
                    }
                }
            }
        }
    }

    public suspend fun createSuggestedSessions(
        tasks: List<SuggestedSubagentTask>,
        initialSettings: KodexAgentSettings,
    ): List<CreatedSuggestedSession> = host.inBackend {
        commands.withLock {
            val created = tasks.map { task ->
                val index = create(initialSettings) { task.name }
                CreatedSuggestedSession(index, SuggestedSessionMeta(runtime.getStorageUri(index), task.name))
            }
            created.zip(tasks).forEach { (createdSession, task) ->
                val index = createdSession.sessionIndex
                // This scope is owned by the original Session, not the RPC waiter.
                host.session(index).operations.launch {
                    try {
                        runtime.appendUserMessage(index, listOf(ContentItem.InputText(task.prompt)))
                        runtime.resume(index)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        reportBackgroundFailure(failure)
                    }
                }
            }
            created
        }
    }

    private suspend fun create(initialSettings: KodexAgentSettings, name: (Int) -> String): Int {
        val index = host.repository().create()
        try {
            host.keepSessionAlive(index)
            host.inSession(index) {
                runtime.modify { it.initialize(initialSettings.copy(threadName = name(index))) }
            }
            return index
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                runCatching { host.deleteSession(index) }.onFailure(failure::addSuppressed)
            }
            throw failure
        }
    }

    private suspend fun requireEntry(index: Int) {
        if (index !in host.repository().entries.value) throw SessionNotFound()
    }
}
