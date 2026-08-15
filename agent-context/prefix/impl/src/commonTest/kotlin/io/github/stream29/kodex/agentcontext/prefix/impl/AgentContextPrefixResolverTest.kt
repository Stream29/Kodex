package io.github.stream29.kodex.agentcontext.prefix.impl

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentcontext.prefix.agentsmd.contract.AgentsMdInstruction
import io.github.stream29.kodex.agentcontext.contract.AgentContextSettings
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

val agentContextPrefixResolverTest by testSuite {
    test("resolves hierarchical AGENTS.md from real files") {
        val root = Path(SystemTemporaryDirectory, "kodex-context-${Random.nextLong()}")
        val home = Path(root, "home")
        val agentsHome = Path(home, ".agents")
        val codexHome = Path(home, ".codex")
        val project = Path(root, "project")
        val cwd = Path(project, "module")
        val skill = Path(agentsHome, "skills/gradle")
        val ignoredCodexSkill = Path(codexHome, "skills/ignored")
        SystemCoroutineFileSystem.createDirectories(Path(project, ".git"))
        SystemCoroutineFileSystem.createDirectories(cwd)
        SystemCoroutineFileSystem.createDirectories(agentsHome)
        SystemCoroutineFileSystem.createDirectories(codexHome)
        SystemCoroutineFileSystem.createDirectories(skill)
        SystemCoroutineFileSystem.createDirectories(ignoredCodexSkill)
        try {
            SystemCoroutineFileSystem.writeString(Path(root, "AGENTS.md"), "outside project")
            SystemCoroutineFileSystem.writeString(Path(agentsHome, "AGENTS.md"), "user rules")
            SystemCoroutineFileSystem.writeString(Path(codexHome, "AGENTS.md"), "ignored Codex rules")
            SystemCoroutineFileSystem.writeString(Path(project, "AGENTS.md"), "project rules")
            SystemCoroutineFileSystem.writeString(Path(cwd, "AGENTS.md"), "shadowed module rules")
            SystemCoroutineFileSystem.writeString(Path(cwd, "AGENTS.override.md"), "module override")
            writeSkill(skill, "gradle", "Build Gradle projects.")
            writeSkill(ignoredCodexSkill, "ignored", "Ignored Codex skill.")

            val resolver = AgentContextPrefixResolver(
                contextSettings = MutableStateFlow(testContextSettings(agentsHome, testShell)),
            )
            val settings = settings(cwd)
            val prefix = resolver.resolve(settings)

            assertEquals("user rules", prefix.agentMd.userInstruction?.text)
            assertEquals(
                listOf("project rules", "module override"),
                prefix.agentMd.projectInstructions.map(AgentsMdInstruction::text),
            )
            assertEquals(
                SystemCoroutineFileSystem.resolve(Path(agentsHome, "AGENTS.md")),
                prefix.agentMd.userInstruction?.source,
            )
            assertEquals(
                listOf(
                    SystemCoroutineFileSystem.resolve(Path(project, "AGENTS.md")),
                    SystemCoroutineFileSystem.resolve(Path(cwd, "AGENTS.override.md")),
                ),
                prefix.agentMd.projectInstructions.map(AgentsMdInstruction::source),
            )
            assertTrue(
                prefix.availableSkills.any { available ->
                    available.name == "gradle" &&
                        available.path == SystemCoroutineFileSystem.resolve(Path(skill, "SKILL.md"))
                },
            )
            assertTrue(prefix.availableSkills.none { available -> available.name == "ignored" })

            SystemCoroutineFileSystem.writeString(Path(cwd, "AGENTS.override.md"), "updated module override")
            assertEquals(
                "updated module override",
                resolver.resolve(settings).agentMd.projectInstructions.last().text,
            )
        } finally {
            deleteRecursively(root)
        }
    }

    test("returns only environment context when no project documents exist") {
        val root = Path(SystemTemporaryDirectory, "kodex-empty-context-${Random.nextLong()}")
        val home = Path(root, "home")
        val cwd = Path(root, "working")
        SystemCoroutineFileSystem.createDirectories(home)
        SystemCoroutineFileSystem.createDirectories(cwd)
        try {
            val resolver = AgentContextPrefixResolver(
                contextSettings = MutableStateFlow(
                    testContextSettings(Path(home, ".agents"), testShell),
                ),
            )
            val prefix = resolver.resolve(settings(cwd))

            assertEquals(null, prefix.agentMd.userInstruction)
            assertTrue(prefix.agentMd.projectInstructions.isEmpty())
            assertEquals(cwd, prefix.cwd)
            assertEquals(testShell, prefix.shell)
        } finally {
            deleteRecursively(root)
        }
    }

    test("uses the current settings cwd for environment and AGENTS.md discovery") {
        val root = Path(SystemTemporaryDirectory, "kodex-changing-context-${Random.nextLong()}")
        val firstHome = Path(root, "first-home")
        val secondHome = Path(root, "second-home")
        val first = Path(root, "first")
        val second = Path(root, "second")
        val firstSkill = Path(firstHome, "skills/first")
        val secondSkill = Path(secondHome, "skills/second")
        SystemCoroutineFileSystem.createDirectories(firstHome)
        SystemCoroutineFileSystem.createDirectories(secondHome)
        SystemCoroutineFileSystem.createDirectories(Path(first, ".git"))
        SystemCoroutineFileSystem.createDirectories(Path(second, ".git"))
        SystemCoroutineFileSystem.createDirectories(firstSkill)
        SystemCoroutineFileSystem.createDirectories(secondSkill)
        try {
            SystemCoroutineFileSystem.writeString(Path(firstHome, "AGENTS.md"), "first user rules")
            SystemCoroutineFileSystem.writeString(Path(secondHome, "AGENTS.md"), "second user rules")
            SystemCoroutineFileSystem.writeString(Path(first, "AGENTS.md"), "first rules")
            SystemCoroutineFileSystem.writeString(Path(second, "AGENTS.md"), "second rules")
            writeSkill(firstSkill, "first", "First Agents home.")
            writeSkill(secondSkill, "second", "Second Agents home.")
            val contextSettings = MutableStateFlow(testContextSettings(firstHome, testShell))
            val resolver = AgentContextPrefixResolver(
                contextSettings = contextSettings,
            )

            val initial = resolver.resolve(settings(first))
            assertEquals("first user rules", initial.agentMd.userInstruction?.text)
            assertEquals("first rules", initial.agentMd.projectInstructions.single().text)
            assertTrue(
                initial.availableSkills.any { skill ->
                    skill.name == "first" &&
                        skill.path == SystemCoroutineFileSystem.resolve(Path(firstSkill, "SKILL.md"))
                },
            )
            assertEquals(testShell, initial.shell)

            contextSettings.value = testContextSettings(secondHome, testZsh)
            val updated = resolver.resolve(settings(second))

            assertEquals("second user rules", updated.agentMd.userInstruction?.text)
            assertEquals("second rules", updated.agentMd.projectInstructions.single().text)
            assertTrue(
                updated.availableSkills.any { skill ->
                    skill.name == "second" &&
                        skill.path == SystemCoroutineFileSystem.resolve(Path(secondSkill, "SKILL.md"))
                },
            )
            assertEquals(second, updated.cwd)
            assertEquals(testZsh, updated.shell)
        } finally {
            deleteRecursively(root)
        }
    }
}

private fun settings(cwd: Path): KodexAgentSettings =
    KodexAgentSettings(model = OpenAiModelId("test-model"), cwd = cwd)

private fun testContextSettings(agentsHome: Path, shell: Shell): AgentContextSettings =
    TestAgentContextSettings(agentsHome, shell)

private data class TestAgentContextSettings(
    override val agentsHome: Path,
    override val shell: Shell,
) : AgentContextSettings

private val testShell: Shell = Shell(ShellType.Bash, Path("/bin/bash"))
private val testZsh: Shell = Shell(ShellType.Zsh, Path("/bin/zsh"))

private suspend fun writeSkill(directory: Path, name: String, description: String) {
    SystemCoroutineFileSystem.writeString(
        Path(directory, "SKILL.md"),
        """
        ---
        name: $name
        description: $description
        ---
        Skill instructions.
        """.trimIndent(),
    )
}

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { child -> deleteRecursively(child) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
