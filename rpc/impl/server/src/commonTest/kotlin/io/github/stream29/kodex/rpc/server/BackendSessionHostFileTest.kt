@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.filesystem.FileSystemKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.time.TestTimeSource
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

val backendSessionHostFileTest by testSuite(testConfig = TestConfig.testScope(isEnabled = false)) {
    test("expired file owner releases its lease before reopening persisted history") {
        runTest {
            // Keep the monotonic clock explicit while real filesystem work
            // suspends; scheduler auto-advancement must not consume the TTL.
            backgroundScope.async {
                val root = Path(SystemTemporaryDirectory, "kodex-rpc-host-${Random.nextLong()}")
                val clock = TestTimeSource()
                try {
                    withBackendSessionHost(clock, {
                        FileSystemKodexSessionRepository(root, testKodexAgentDependencies())
                    }) { host ->
                        val index = host.repository.create()
                        host.keepSessionAlive(index)
                        host.inSession(index) {
                            runtime.modify { it.initialize(KodexAgentSettings(model = OpenAiModelId("test-model"))) }
                        }
                        val old = host.session(index)
                        val settings = old.session.storage.settings[0]
                        clock += 60.seconds
                        advanceTimeBy(60_000)
                        runCurrent()
                        old.inactive.await()
                        assertTrue(old.session.coroutineContext.job.isCompleted)
                        host.keepSessionAlive(index)
                        val fresh = host.session(index)
                        assertNotSame(old.session, fresh.session)
                        assertEquals(settings, fresh.session.storage.settings[0])
                        assertTrue(host.deleteSession(index))
                        assertTrue(fresh.inactive.isCompleted)
                        assertEquals(emptyList(), host.repository.entries.value)
                    }
                } finally {
                    deleteTestTree(root)
                }
            }.await()
        }
    }
}

private suspend fun deleteTestTree(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) SystemCoroutineFileSystem.list(path).forEach { deleteTestTree(it) }
    SystemCoroutineFileSystem.delete(path)
}
