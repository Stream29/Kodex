package io.github.stream29.kodex.agentcontext.prefix.agentsmd.filesystem

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentcontext.contract.AgentContextSourcePlan
import io.github.stream29.kodex.agentcontext.prefix.agentsmd.contract.AgentsMdWarning
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

val agentsMdReadFailureTest by testSuite {
    test("ordinary source read failure remains a warning") {
        withReadFailure(IOException("Synthetic read failure")) { plan, fs ->
            val snapshot = loadAgentsMd(plan, fileSystem = fs)
            assertIs<AgentsMdWarning.ReadFailed>(snapshot.warnings.single())
        }
    }
    test("operation cancellation propagates while its caller remains active") {
        val cancelled = CancellationException("Synthetic operation cancellation")
        withReadFailure(cancelled) { plan, fs ->
            val actual = assertFailsWith<CancellationException> { loadAgentsMd(plan, fileSystem = fs) }
            assertSame(cancelled, actual)
            currentCoroutineContext().ensureActive()
        }
    }
    test("implementation errors are not downgraded to read warnings") {
        val error = AssertionError("Synthetic read implementation error")
        withReadFailure(error) { plan, fs ->
            val actual = assertFailsWith<AssertionError> { loadAgentsMd(plan, fileSystem = fs) }
            assertSame(error, actual)
        }
    }
}

private suspend fun withReadFailure(
    failure: Throwable,
    block: suspend (AgentContextSourcePlan, CoroutineFileSystem) -> Unit,
) {
    val root = Path(SystemTemporaryDirectory, "kodex-agents-read-failure-${Random.nextLong()}")
    SystemCoroutineFileSystem.createDirectories(root)
    val file = Path(root, "AGENTS.md")
    try {
        SystemCoroutineFileSystem.writeString(file, "Synthetic test instruction")
        val fs = object : CoroutineFileSystem by SystemCoroutineFileSystem {
            override suspend fun readBytes(path: Path, maxByteCount: Long): ByteArray = throw failure
        }
        block(AgentContextSourcePlan(globalRoots = listOf(root), projectRoots = emptyList()), fs)
    } finally {
        SystemCoroutineFileSystem.delete(file, mustExist = false)
        SystemCoroutineFileSystem.delete(root, mustExist = false)
    }
}
