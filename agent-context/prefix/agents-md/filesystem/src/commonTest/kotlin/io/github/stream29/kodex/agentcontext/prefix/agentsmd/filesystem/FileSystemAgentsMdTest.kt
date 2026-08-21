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
    test("loads the four endpoint layers, ignores overrides, and refreshes") {
        val root = temporaryDirectory("agents-md-layers")
        val agentsHome = Path(root, "agents-home")
        val kodexHome = Path(root, "kodex-home")
        val project = Path(root, "project")
        val intermediate = Path(project, "module")
        val cwd = Path(intermediate, "leaf")
        SystemCoroutineFileSystem.createDirectories(agentsHome)
        SystemCoroutineFileSystem.createDirectories(kodexHome)
        SystemCoroutineFileSystem.createDirectories(Path(project, ".git"))
        SystemCoroutineFileSystem.createDirectories(intermediate)
        SystemCoroutineFileSystem.createDirectories(cwd)
        try {
            SystemCoroutineFileSystem.writeString(Path(agentsHome, "AGENTS.md"), "Agents rules")
            SystemCoroutineFileSystem.writeString(Path(agentsHome, "AGENTS.override.md"), "Ignored Agents override")
            SystemCoroutineFileSystem.writeString(Path(kodexHome, "AGENTS.md"), "Kodex rules")
            SystemCoroutineFileSystem.writeString(Path(kodexHome, "AGENTS.override.md"), "Ignored Kodex override")
            SystemCoroutineFileSystem.writeString(Path(project, "AGENTS.md"), "Git rules")
            SystemCoroutineFileSystem.writeString(Path(intermediate, "AGENTS.md"), "Ignored intermediate rules")
            SystemCoroutineFileSystem.writeString(Path(cwd, "AGENTS.md"), "Cwd rules")
            SystemCoroutineFileSystem.writeString(Path(cwd, "AGENTS.override.md"), "Ignored cwd override")
            val initial = loadAgentsMd(agentsHome, kodexHome, cwd)

            assertEquals(
                listOf("Agents rules", "Kodex rules"),
                initial.instructions.globalInstructions.map(AgentsMdInstruction::text),
            )
            assertEquals(
                listOf("Git rules", "Cwd rules"),
                initial.instructions.projectInstructions.map(AgentsMdInstruction::text),
            )
            assertEquals(
                listOf(
                    SystemCoroutineFileSystem.resolve(Path(agentsHome, "AGENTS.md")),
                    SystemCoroutineFileSystem.resolve(Path(kodexHome, "AGENTS.md")),
                    SystemCoroutineFileSystem.resolve(Path(project, "AGENTS.md")),
                    SystemCoroutineFileSystem.resolve(Path(cwd, "AGENTS.md")),
                ),
                initial.instructions.globalInstructions.map(AgentsMdInstruction::source) +
                    initial.instructions.projectInstructions.map(AgentsMdInstruction::source),
            )

            SystemCoroutineFileSystem.writeString(Path(cwd, "AGENTS.md"), "Updated cwd rules")
            val refreshed = loadAgentsMd(agentsHome, kodexHome, cwd)
            assertEquals("Updated cwd rules", refreshed.instructions.projectInstructions.last().text)
            assertTrue(refreshed.warnings.isEmpty())
        } finally {
            deleteRecursively(root)
        }
    }

    test("deduplicates physical endpoint roots and omits a missing Git layer") {
        val root = temporaryDirectory("agents-md-root-deduplication")
        val agentsHome = Path(root, "agents-home")
        val kodexHome = Path(root, "kodex-home")
        val cwd = Path(root, "working")
        SystemCoroutineFileSystem.createDirectories(agentsHome)
        SystemCoroutineFileSystem.createDirectories(kodexHome)
        SystemCoroutineFileSystem.createDirectories(cwd)
        try {
            SystemCoroutineFileSystem.writeString(Path(agentsHome, "AGENTS.md"), "Agents rules")
            SystemCoroutineFileSystem.writeString(Path(kodexHome, "AGENTS.md"), "Kodex rules")
            SystemCoroutineFileSystem.writeString(Path(cwd, "AGENTS.md"), "Cwd rules")
            val withoutGit = loadAgentsMd(agentsHome, kodexHome, cwd)

            assertEquals(
                listOf("Agents rules", "Kodex rules"),
                withoutGit.instructions.globalInstructions.map(AgentsMdInstruction::text),
            )
            assertEquals(
                listOf("Cwd rules"),
                withoutGit.instructions.projectInstructions.map(AgentsMdInstruction::text),
            )

            SystemCoroutineFileSystem.createDirectories(Path(cwd, ".git"))
            val rootEqualsCwd = loadAgentsMd(agentsHome, kodexHome, cwd)

            assertEquals(
                listOf("Cwd rules"),
                rootEqualsCwd.instructions.projectInstructions.map(AgentsMdInstruction::text),
            )
        } finally {
            deleteRecursively(root)
        }
    }

    test("reports lossy decoding and keeps global documents outside the project budget") {
        val root = temporaryDirectory("agents-md-warning")
        val agentsHome = Path(root, "agents-home")
        val kodexHome = Path(root, "kodex-home")
        val project = Path(root, "project")
        val cwd = Path(project, "module")
        SystemCoroutineFileSystem.createDirectories(agentsHome)
        SystemCoroutineFileSystem.createDirectories(kodexHome)
        SystemCoroutineFileSystem.createDirectories(Path(project, ".git"))
        SystemCoroutineFileSystem.createDirectories(cwd)
        try {
            SystemCoroutineFileSystem.writeString(Path(agentsHome, "AGENTS.md"), "Agents document")
            SystemCoroutineFileSystem.writeString(Path(kodexHome, "AGENTS.md"), "Kodex document")
            SystemCoroutineFileSystem.writeBytes(
                Path(project, "AGENTS.md"),
                byteArrayOf(0xC3.toByte(), 0x28, 'a'.code.toByte(), 'b'.code.toByte()),
            )
            SystemCoroutineFileSystem.writeString(Path(cwd, "AGENTS.md"), "Cwd document")
            val snapshot = loadAgentsMd(
                agentsHome = agentsHome,
                kodexHome = kodexHome,
                cwd = cwd,
                projectDocMaxBytes = 3,
            )

            assertEquals(
                listOf("Agents document", "Kodex document"),
                snapshot.instructions.globalInstructions.map(AgentsMdInstruction::text),
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
