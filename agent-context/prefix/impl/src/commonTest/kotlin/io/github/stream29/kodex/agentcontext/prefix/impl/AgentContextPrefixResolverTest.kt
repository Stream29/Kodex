package io.github.stream29.kodex.agentcontext.prefix.impl

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentcontext.prefix.agentsmd.contract.AgentsMdInstruction
import io.github.stream29.kodex.agentcontext.contract.AgentContextSettings
import io.github.stream29.kodex.agentcontext.contract.AgentContextSourceSettings
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
    test("resolves four-layer AGENTS.md and Skills from real files") {
        val root = Path(SystemTemporaryDirectory, "kodex-context-${Random.nextLong()}")
        val home = Path(root, "home")
        val agentsHome = Path(home, ".agents")
        val kodexHome = Path(home, ".kodex")
        val project = Path(root, "project")
        val cwd = Path(project, "module")
        val skill = Path(agentsHome, "skills/gradle")
        val kodexSkill = Path(kodexHome, "skills/kodex")
        SystemCoroutineFileSystem.createDirectories(Path(project, ".git"))
        SystemCoroutineFileSystem.createDirectories(cwd)
        SystemCoroutineFileSystem.createDirectories(agentsHome)
        SystemCoroutineFileSystem.createDirectories(kodexHome)
        SystemCoroutineFileSystem.createDirectories(skill)
        SystemCoroutineFileSystem.createDirectories(kodexSkill)
        try {
            SystemCoroutineFileSystem.writeString(Path(root, "AGENTS.md"), "outside project")
            SystemCoroutineFileSystem.writeString(Path(agentsHome, "AGENTS.md"), "user rules")
            SystemCoroutineFileSystem.writeString(Path(kodexHome, "AGENTS.md"), "Kodex rules")
            SystemCoroutineFileSystem.writeString(Path(project, "AGENTS.md"), "project rules")
            SystemCoroutineFileSystem.writeString(Path(cwd, "AGENTS.md"), "shadowed module rules")
            SystemCoroutineFileSystem.writeString(Path(cwd, "AGENTS.override.md"), "ignored module override")
            writeSkill(skill, "gradle", "Build Gradle projects.")
            writeSkill(kodexSkill, "kodex", "Kodex Home skill.")

            val resolver = AgentContextPrefixResolver(
                contextSettings = MutableStateFlow(testContextSettings(agentsHome, kodexHome, testShell)),
            )
            val settings = settings(cwd)
            val prefix = resolver.resolve(settings)

            assertEquals(
                listOf("user rules", "Kodex rules"),
                prefix.agentMd.globalInstructions.map(AgentsMdInstruction::text),
            )
            assertEquals(
                listOf("project rules", "shadowed module rules"),
                prefix.agentMd.projectInstructions.map(AgentsMdInstruction::text),
            )
            assertEquals(
                listOf(
                    SystemCoroutineFileSystem.resolve(Path(agentsHome, "AGENTS.md")),
                    SystemCoroutineFileSystem.resolve(Path(kodexHome, "AGENTS.md")),
                    SystemCoroutineFileSystem.resolve(Path(project, "AGENTS.md")),
                    SystemCoroutineFileSystem.resolve(Path(cwd, "AGENTS.md")),
                ),
                prefix.agentMd.globalInstructions.map(AgentsMdInstruction::source) +
                    prefix.agentMd.projectInstructions.map(AgentsMdInstruction::source),
            )
            assertTrue(
                prefix.availableSkills.any { available ->
                    available.name == "gradle" &&
                    available.path == SystemCoroutineFileSystem.resolve(Path(skill, "SKILL.md"))
                },
            )
            assertTrue(
                prefix.availableSkills.any { available ->
                    available.name == "kodex" &&
                        available.path == SystemCoroutineFileSystem.resolve(Path(kodexSkill, "SKILL.md"))
                },
            )

            SystemCoroutineFileSystem.writeString(Path(cwd, "AGENTS.md"), "updated module rules")
            assertEquals(
                "updated module rules",
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
                    testContextSettings(Path(home, ".agents"), Path(home, ".kodex"), testShell),
                ),
            )
            val prefix = resolver.resolve(settings(cwd))

            assertTrue(prefix.agentMd.globalInstructions.isEmpty())
            assertTrue(prefix.agentMd.projectInstructions.isEmpty())
            assertEquals(cwd, prefix.cwd)
            assertEquals(testShell, prefix.shell)
        } finally {
            deleteRecursively(root)
        }
    }

    test("uses the current settings cwd for environment and AGENTS.md discovery") {
        val root = Path(SystemTemporaryDirectory, "kodex-changing-context-${Random.nextLong()}")
        val firstAgentsHome = Path(root, "first-agents-home")
        val secondAgentsHome = Path(root, "second-agents-home")
        val firstKodexHome = Path(root, "first-kodex-home")
        val secondKodexHome = Path(root, "second-kodex-home")
        val first = Path(root, "first")
        val second = Path(root, "second")
        val firstSkill = Path(firstAgentsHome, "skills/first")
        val secondSkill = Path(secondAgentsHome, "skills/second")
        val firstKodexSkill = Path(firstKodexHome, "skills/first-kodex")
        val secondKodexSkill = Path(secondKodexHome, "skills/second-kodex")
        SystemCoroutineFileSystem.createDirectories(firstAgentsHome)
        SystemCoroutineFileSystem.createDirectories(secondAgentsHome)
        SystemCoroutineFileSystem.createDirectories(firstKodexHome)
        SystemCoroutineFileSystem.createDirectories(secondKodexHome)
        SystemCoroutineFileSystem.createDirectories(Path(first, ".git"))
        SystemCoroutineFileSystem.createDirectories(Path(second, ".git"))
        SystemCoroutineFileSystem.createDirectories(firstSkill)
        SystemCoroutineFileSystem.createDirectories(secondSkill)
        SystemCoroutineFileSystem.createDirectories(firstKodexSkill)
        SystemCoroutineFileSystem.createDirectories(secondKodexSkill)
        try {
            SystemCoroutineFileSystem.writeString(Path(firstAgentsHome, "AGENTS.md"), "first Agents rules")
            SystemCoroutineFileSystem.writeString(Path(secondAgentsHome, "AGENTS.md"), "second Agents rules")
            SystemCoroutineFileSystem.writeString(Path(firstKodexHome, "AGENTS.md"), "first Kodex rules")
            SystemCoroutineFileSystem.writeString(Path(secondKodexHome, "AGENTS.md"), "second Kodex rules")
            SystemCoroutineFileSystem.writeString(Path(first, "AGENTS.md"), "first rules")
            SystemCoroutineFileSystem.writeString(Path(second, "AGENTS.md"), "second rules")
            writeSkill(firstSkill, "first", "First Agents home.")
            writeSkill(secondSkill, "second", "Second Agents home.")
            writeSkill(firstKodexSkill, "first-kodex", "First Kodex Home.")
            writeSkill(secondKodexSkill, "second-kodex", "Second Kodex Home.")
            val contextSettings = MutableStateFlow(
                testContextSettings(firstAgentsHome, firstKodexHome, testShell),
            )
            val resolver = AgentContextPrefixResolver(
                contextSettings = contextSettings,
            )

            val initial = resolver.resolve(settings(first))
            assertEquals(
                listOf("first Agents rules", "first Kodex rules"),
                initial.agentMd.globalInstructions.map(AgentsMdInstruction::text),
            )
            assertEquals("first rules", initial.agentMd.projectInstructions.single().text)
            assertTrue(
                initial.availableSkills.any { skill ->
                    skill.name == "first" &&
                    skill.path == SystemCoroutineFileSystem.resolve(Path(firstSkill, "SKILL.md"))
                },
            )
            assertTrue(
                initial.availableSkills.any { skill ->
                    skill.name == "first-kodex" &&
                        skill.path == SystemCoroutineFileSystem.resolve(Path(firstKodexSkill, "SKILL.md"))
                },
            )
            assertEquals(testShell, initial.shell)

            contextSettings.value = testContextSettings(secondAgentsHome, secondKodexHome, testZsh)
            val updated = resolver.resolve(settings(second))

            assertEquals(
                listOf("second Agents rules", "second Kodex rules"),
                updated.agentMd.globalInstructions.map(AgentsMdInstruction::text),
            )
            assertEquals("second rules", updated.agentMd.projectInstructions.single().text)
            assertTrue(
                updated.availableSkills.any { skill ->
                    skill.name == "second" &&
                    skill.path == SystemCoroutineFileSystem.resolve(Path(secondSkill, "SKILL.md"))
                },
            )
            assertTrue(
                updated.availableSkills.any { skill ->
                    skill.name == "second-kodex" &&
                        skill.path == SystemCoroutineFileSystem.resolve(Path(secondKodexSkill, "SKILL.md"))
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

private fun testContextSettings(
    agentsHome: Path,
    kodexHome: Path,
    shell: Shell,
    codexHome: Path = Path(kodexHome, "codex-home"),
): AgentContextSettings = TestAgentContextSettings(agentsHome, kodexHome, codexHome, shell)

private data class TestAgentContextSettings(
    override val agentsHome: Path,
    override val kodexHome: Path,
    override val codexHome: Path,
    override val shell: Shell,
    override val sources: AgentContextSourceSettings = AgentContextSourceSettings(),
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
