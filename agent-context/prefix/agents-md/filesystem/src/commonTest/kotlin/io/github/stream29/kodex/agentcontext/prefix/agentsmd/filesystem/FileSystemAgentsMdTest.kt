package io.github.stream29.kodex.agentcontext.prefix.agentsmd.filesystem

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentcontext.prefix.agentsmd.contract.AgentsMdInstruction
import io.github.stream29.kodex.agentcontext.prefix.agentsmd.contract.AgentsMdWarning
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

val fileSystemAgentsMdTest by testSuite {
    test("loads precedence and returns filesystem changes on the next invocation") {
        val root = temporaryDirectory("agents-md-refresh")
        val agentsHome = Path(root, "agents-home")
        val project = Path(root, "project")
        val cwd = Path(project, "module")
        SystemCoroutineFileSystem.createDirectories(agentsHome)
        SystemCoroutineFileSystem.createDirectories(Path(project, ".git"))
        SystemCoroutineFileSystem.createDirectories(cwd)
        try {
            SystemCoroutineFileSystem.writeString(Path(agentsHome, "AGENTS.md"), "ignored user rules")
            SystemCoroutineFileSystem.writeString(Path(agentsHome, "AGENTS.override.md"), "user rules")
            SystemCoroutineFileSystem.writeString(Path(project, "AGENTS.md"), "project rules")
            SystemCoroutineFileSystem.writeString(Path(cwd, "AGENTS.md"), "ignored module rules")
            SystemCoroutineFileSystem.writeString(Path(cwd, "AGENTS.override.md"), "module rules")
            val initial = loadAgentsMd(agentsHome, cwd)

            assertEquals("user rules", initial.instructions.userInstruction?.text)
            assertEquals(
                listOf("project rules", "module rules"),
                initial.instructions.projectInstructions.map(AgentsMdInstruction::text),
            )

            SystemCoroutineFileSystem.writeString(Path(cwd, "AGENTS.override.md"), "updated module rules")
            val refreshed = loadAgentsMd(agentsHome, cwd)
            assertEquals("updated module rules", refreshed.instructions.projectInstructions.last().text)
            assertTrue(refreshed.warnings.isEmpty())
        } finally {
            deleteRecursively(root)
        }
    }

    test("reports lossy decoding and project byte truncation") {
        val root = temporaryDirectory("agents-md-warning")
        val project = Path(root, "project")
        SystemCoroutineFileSystem.createDirectories(Path(project, ".git"))
        try {
            SystemCoroutineFileSystem.writeBytes(
                Path(project, "AGENTS.md"),
                byteArrayOf(0xC3.toByte(), 0x28, 'a'.code.toByte(), 'b'.code.toByte()),
            )
            val snapshot = loadAgentsMd(
                agentsHome = Path(root, "agents-home"),
                cwd = project,
                projectDocMaxBytes = 3,
            )

            assertEquals(1, snapshot.instructions.projectInstructions.size)
            val invalidUtf8 = snapshot.warnings.filterIsInstance<AgentsMdWarning.InvalidUtf8>()
            val truncated = snapshot.warnings.filterIsInstance<AgentsMdWarning.Truncated>()
            assertEquals(1, invalidUtf8.size)
            assertEquals(4L, truncated.single().originalByteCount)
            assertEquals(3, truncated.single().acceptedByteCount)
        } finally {
            deleteRecursively(root)
        }
    }
}

private fun temporaryDirectory(name: String): Path =
    Path(SystemTemporaryDirectory, "kodex-$name-${Random.nextLong()}")

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { child -> deleteRecursively(child) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
