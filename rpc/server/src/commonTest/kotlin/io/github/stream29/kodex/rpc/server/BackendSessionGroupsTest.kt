package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.contract.AgentRuntimeRpc
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.inmemory.withInMemoryRpc
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.rpc.withService
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val backendSessionGroupsTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("two independent RPC clients share committed group changes and retain Session addressing") {
        withServices { services, _ ->
            val home = services.settings.contextSettings.value.kodexHome
            val oldCwd = Path(home, "project")
            val newCwd = Path(home, "renamed-project")
            SystemCoroutineFileSystem.createDirectories(oldCwd)
            SystemCoroutineFileSystem.createDirectories(newCwd)
            val oldPath = SystemCoroutineFileSystem.resolve(oldCwd).toString()
            val newPath = SystemCoroutineFileSystem.resolve(newCwd).toString()
            withInMemoryRpc(services::register) { writes ->
                withInMemoryRpc(services::register) { reads ->
                    val mutator = RestoringRpcClient(writes).withService<GlobalRpc>()
                    val runtime = RestoringRpcClient(writes).withService<AgentRuntimeRpc>()
                    val observer = RestoringRpcClient(reads).withService<GlobalRpc>()
                    suspend fun change(nonces: Flow<Long>, action: suspend () -> Unit) = coroutineScope {
                        val events = nonces.produceIn(this)
                        try {
                            val before = withTimeout(5.seconds) { events.receive() }
                            action()
                            withTimeout(5.seconds) {
                                while (events.receive() == before) { /* Drain repeats. */ }
                            }
                        } finally {
                            events.cancel()
                        }
                    }

                    assertTrue(observer.listSessionGroups().isEmpty())
                    var index = -1
                    change(observer.getSessionGroupsNonceFlow()) { index = mutator.createSession(oldCwd) }
                    val root = mutator.getRootAgentId(index)
                    val page = observer.listSessionsInGroup(oldPath, false, 5)
                    assertEquals(index, page.entries.single().sessionIndex)
                    assertEquals(root, page.entries.single().rootAgentId)
                    assertEquals(0, page.hiddenCount)

                    runtime.keepAgentAlive(root)
                    val settings = runtime.getLastCommitsIn(root, runtime.getCacheNonce(root), 0, Int.MAX_VALUE,
                        setOf(AgentCommitType.Settings), 1).single().commit as KodexAgentSettings
                    val renamed = settings.copy(name = "Grouped fixture")
                    change(observer.getSessionGroupNonceFlow(oldPath)) {
                        assertTrue(runtime.compareAndSetSettings(root, settings, renamed))
                    }
                    assertEquals("Grouped fixture", observer.listSessionsInGroup(oldPath, false).entries.single().name)
                    change(observer.getSessionGroupsNonceFlow()) {
                        assertTrue(runtime.compareAndSetSettings(root, renamed, renamed.copy(cwd = newCwd)))
                    }
                    assertEquals(listOf(newPath), observer.listSessionGroups().map { it.cwd })
                    assertTrue(observer.listSessionsInGroup(oldPath, true).entries.isEmpty())
                    change(observer.getSessionGroupNonceFlow(newPath)) { mutator.archiveSession(index) }
                    assertEquals(newPath, observer.listSessionGroups().single().cwd)
                    assertTrue(observer.listSessionsInGroup(newPath, false, 5).entries.isEmpty())
                    assertTrue(observer.listSessionsInGroup(newPath, true, 5).entries.single().archived)
                    change(observer.getSessionGroupNonceFlow(newPath)) { mutator.unarchiveSession(index) }
                    assertEquals(index, observer.getSessionCatalog(false).single().sessionIndex)
                    change(observer.getSessionGroupNonceFlow(newPath)) { assertTrue(mutator.deleteSession(index)) }
                    assertTrue(observer.listSessionGroups().isEmpty())
                }
            }
        }
    }
}
