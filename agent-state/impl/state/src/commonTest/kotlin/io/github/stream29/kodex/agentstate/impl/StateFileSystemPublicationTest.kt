package io.github.stream29.kodex.agentstate.impl

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.contract.forcedCompact
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.agentstorage.filesystem.FileSystemAgentStorage
import io.github.stream29.kodex.agentstorage.filesystem.ofEmpty
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.RemoteCompactionV2Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Real numbered-file faults, not a replacement CAS/storage algorithm.
 * B2 desired-invariant probes are central confirmation gates, not a claim that
 * cross-timeline writes are transactional or that the Session cache was tested.
 */
val stateFileSystemPublicationTest by testSuite {
    testFixture {
        testSuiteCoroutineScope.supervisorChildScope()
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
        for (cas in listOf(false, true)) {
            for (cancelled in listOf(false, true)) {
                test("B2 timestamp ${if (cancelled) "cancellation" else "failure"} resyncs durable settings with cas=$cas") {
                    withStateDirectory { directory ->
                        val primary = if (cancelled) CancellationException("timestamp cancelled")
                        else IOException("timestamp write failed")
                        val cleanup = IOException("timestamp temporary cleanup failed")
                        val fileSystem = StatePublicationFileSystem()
                        val storage = FileSystemAgentStorage.ofEmpty(directory, fileSystem, mustCreateDirectory = false)
                        storage.initialize(KodexAgentSettings(OpenAiModelId("initial")))
                        val agent = KodexAgentState(mockOpenAiClient(), storage)
                        try {
                            agent.appendUserMessage(listOf(ContentItem.InputText("legal history")))
                            val before = storage.settings[agent.latestIndex.value]
                            val history = storage.index.valuesIn(0..Int.MAX_VALUE)
                            val updated = before.copy(threadName = "durably written")
                            fileSystem.timestampFailure = primary
                            fileSystem.timestampCleanupFailure = cleanup

                            assertSame(primary, observeStateWriteFailure {
                                if (cas) agent.compareAndSetSettings(before, updated)
                                else agent.updateSettings(updated)
                            })

                            assertEquals(listOf(cleanup), primary.suppressedExceptions)
                            assertTrue(fileSystem.cleanupAttempted)
                            val raw = FileSystemAgentStorage(directory)
                            assertEquals(updated, raw.settings.getExact(2))
                            assertEquals(2, raw.latestIndex())
                            assertEquals(1, raw.timestamp.latestIndex())
                            assertEquals("2", SystemCoroutineFileSystem.readString(Path(directory, "settings", "latest.json")))
                            // Baseline B2 failure: appendSettings publishes this only after timestamp.
                            assertEquals(2, agent.latestIndex.value)
                            assertEquals(updated, agent.storage.settings[agent.latestIndex.value])
                            assertEquals(KodexAgentStateValue.UserMessage, agent.state.value)
                            assertEquals(history, raw.index.valuesIn(0..Int.MAX_VALUE))
                            assertFalse(agent.compareAndSetSettings(before, before.copy(threadName = "stale")))
                            assertTrue(agent.compareAndSetSettings(updated, updated.copy(threadName = "next")))
                            assertEquals(3, agent.latestIndex.value)
                            assertEquals(updated, raw.settings.getExact(2))
                            assertEquals(before, raw.settings.getExact(0))
                            assertEquals(listOf(0, 2, 3), raw.settings.indexesIn(0..3))
                            assertEquals(listOf(0, 1, 3), raw.timestamp.indexesIn(0..3))
                        } finally {
                            agent.cancelAndJoin()
                        }
                    }
                }
            }
        }

        test("B2 record publication then cleanup failure resyncs State and never replaces that record") {
            withStateDirectory { directory ->
                val fileSystem = StatePublicationFileSystem()
                val storage = FileSystemAgentStorage.ofEmpty(directory, fileSystem, mustCreateDirectory = false)
                storage.initialize(KodexAgentSettings(OpenAiModelId("initial")))
                val agent = KodexAgentState(mockOpenAiClient(), storage)
                try {
                    val before = storage.settings[0]
                    val published = before.copy(threadName = "published despite failure")
                    val failure = IOException("settings temporary cleanup failed after entry move")
                    fileSystem.settingsCleanupFailure = failure
                    assertSame(failure, observeStateWriteFailure {
                        agent.compareAndSetSettings(before, published)
                    })
                    val raw = FileSystemAgentStorage(directory)
                    assertEquals(published, raw.settings.getExact(1))
                    assertEquals("1", SystemCoroutineFileSystem.readString(Path(directory, "settings", "latest.json")))
                    assertEquals(1, storage.latestIndex())
                    assertEquals(1, agent.latestIndex.value)
                    assertEquals(KodexAgentStateValue.Empty, agent.state.value)
                    assertEquals(emptyList(), failure.suppressedExceptions)
                    assertTrue(agent.compareAndSetSettings(published, published.copy(threadName = "next")))
                    assertEquals(2, agent.latestIndex.value)
                    assertEquals(published, raw.settings.getExact(1))
                    assertEquals(before, raw.settings.getExact(0))
                } finally {
                    agent.cancelAndJoin()
                }
            }
        }

        for (cancelled in listOf(false, true)) {
            test("checkpoint waits for admitted filesystem CAS with cancelled=$cancelled") {
                withStateDirectory { directory ->
                    val fileSystem = StatePublicationFileSystem()
                    val storage = FileSystemAgentStorage.ofEmpty(directory, fileSystem, mustCreateDirectory = false)
                    storage.initialize(KodexAgentSettings(OpenAiModelId("initial")))
                    val networkEntered = CompletableDeferred<Unit>()
                    val networkRelease = CompletableDeferred<Unit>()
                    val timestampEntered = CompletableDeferred<Unit>()
                    val timestampRelease = CompletableDeferred<Unit>()
                    val networkReturned = CompletableDeferred<Unit>()
                    val agent = KodexAgentState(mockOpenAiClient {
                        createRemoteCompactionV2Response {
                            networkEntered.complete(Unit)
                            networkRelease.await()
                            networkReturned.complete(Unit)
                            RemoteCompactionV2Response(ResponseItem.Compaction(encryptedContent = "result"), null)
                        }
                    }, storage)
                    agent.appendUserMessage(listOf(ContentItem.InputText("compact")))
                    val before = storage.settings[agent.latestIndex.value]
                    val compacting = async(start = CoroutineStart.UNDISPATCHED) { agent.forcedCompact() }
                    var writer: kotlinx.coroutines.Deferred<Boolean>? = null
                    try {
                        networkEntered.await()
                        fileSystem.timestampGate = timestampEntered to timestampRelease
                        val latest = before.copy(threadName = "title", model = OpenAiModelId("selected"))
                        val cas = async(start = CoroutineStart.UNDISPATCHED) {
                            agent.compareAndSetSettings(before, latest)
                        }
                        writer = cas
                        timestampEntered.await()
                        assertEquals(latest, FileSystemAgentStorage(directory).settings.getExact(2))
                        assertEquals(1, agent.latestIndex.value)
                        networkRelease.complete(Unit)
                        networkReturned.await()
                        assertFalse(cas.isCompleted)
                        assertFalse(compacting.isCompleted)
                        assertEquals(KodexAgentStateValue.Compacting, agent.state.value)
                        if (cancelled) compacting.cancel()
                        timestampRelease.complete(Unit)
                        assertTrue(cas.await())
                        if (cancelled) {
                            assertFailsWith<CancellationException> { compacting.await() }
                            compacting.join()
                            assertEquals(latest, storage.settings[2])
                            assertEquals(2, agent.latestIndex.value)
                            assertEquals(listOf(0, 2), storage.settings.indexesIn(0..4))
                            assertEquals(listOf(0, 1, 2), storage.timestamp.indexesIn(0..4))
                            assertEquals(emptyList(), storage.work.indexesIn(0..4))
                        } else {
                            assertEquals(4, compacting.await())
                            val committed = storage.settings[4]
                            assertEquals(latest.copy(
                                windowNumber = latest.windowNumber + 1,
                                previousWindowId = latest.windowId,
                                windowId = committed.windowId,
                            ), committed)
                            assertEquals(listOf(0, 2, 3), storage.settings.indexesIn(0..4))
                            assertEquals(listOf(0, 1, 2, 4), storage.timestamp.indexesIn(0..4))
                            assertEquals(4, agent.latestIndex.value)
                        }
                        assertEquals(KodexAgentStateValue.UserMessage, agent.state.value)
                    } finally {
                        timestampRelease.complete(Unit)
                        networkRelease.complete(Unit)
                        writer?.cancelAndJoin()
                        compacting.cancelAndJoin()
                        agent.cancelAndJoin()
                    }
                }
            }
        }
    }
}

private class StatePublicationFileSystem(
    private val delegate: CoroutineFileSystem = SystemCoroutineFileSystem,
) : CoroutineFileSystem by delegate {
    var timestampFailure: Throwable? = null
    var timestampCleanupFailure: Throwable? = null
    var settingsCleanupFailure: Throwable? = null
    var timestampGate: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>? = null
    var cleanupAttempted = false
        private set
    private var failedTimestampTemporary: Path? = null
    private var publishedSettingsTemporary: Path? = null

    override suspend fun writeString(path: Path, content: String, append: Boolean, mustCreate: Boolean) {
        if (path.parent?.name == "timestamp" && path.name.startsWith(".kodex-write-")) {
            timestampGate?.also {
                timestampGate = null
                it.first.complete(Unit)
                it.second.await()
            }
            timestampFailure?.also { failure ->
                timestampFailure = null
                failedTimestampTemporary = path
                if (failure is CancellationException) currentCoroutineContext().cancel(failure)
                throw failure
            }
        }
        delegate.writeString(path, content, append, mustCreate)
    }

    override suspend fun atomicMove(source: Path, destination: Path) {
        delegate.atomicMove(source, destination)
        if (settingsCleanupFailure != null && destination.parent?.name == "settings" &&
            destination.name != "latest.json" && destination.name.endsWith(".json")
        ) {
            publishedSettingsTemporary = source
        }
    }

    override suspend fun delete(path: Path, mustExist: Boolean) {
        if (path == failedTimestampTemporary || path == publishedSettingsTemporary) {
            currentCoroutineContext().ensureActive()
            cleanupAttempted = true
            if (path == failedTimestampTemporary) {
                failedTimestampTemporary = null
                timestampCleanupFailure?.also {
                    timestampCleanupFailure = null
                    throw it
                }
            } else {
                publishedSettingsTemporary = null
                settingsCleanupFailure?.also {
                    settingsCleanupFailure = null
                    throw it
                }
            }
        }
        delegate.delete(path, mustExist)
    }
}

private suspend fun observeStateWriteFailure(operation: suspend () -> Unit): Throwable = coroutineScope {
    val observed = CompletableDeferred<Throwable>()
    launch {
        observed.complete(runCatching { operation() }.exceptionOrNull() ?: error("Expected failure"))
    }.join()
    observed.await()
}

private suspend fun <T> withStateDirectory(block: suspend (Path) -> T): T {
    val root = Path(SystemTemporaryDirectory, "kodex-state-publication-${Random.nextLong()}")
    SystemCoroutineFileSystem.createDirectories(root, mustCreate = true)
    try {
        return block(root)
    } finally {
        withContext(NonCancellable) { deleteStateDirectory(root) }
    }
}

private suspend fun deleteStateDirectory(path: Path) {
    if (SystemCoroutineFileSystem.metadataOrNull(path)?.isDirectory == true) {
        for (child in SystemCoroutineFileSystem.list(path)) deleteStateDirectory(child)
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
