package io.github.stream29.kodex.agentcontext.skill.filesystem

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentcontext.contract.AgentContextSettings
import io.github.stream29.kodex.agentcontext.skill.contract.SkillResourceResult
import io.github.stream29.kodex.agentcontext.prefix.skill.contract.SkillScope
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSource
import io.github.stream29.kodex.utils.kotlinxiocoroutines.FileFingerprint
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

val fileSystemSkillsResolverTest by testSuite {
    test("discovers four-layer roots while ignoring Home .system skills") {
        val root = temporaryDirectory("skills-catalog")
        val agentsHome = Path(root, "home/.agents")
        val kodexHome = Path(root, "kodex-home")
        val project = Path(root, "project")
        val intermediate = Path(project, "module")
        val cwd = Path(intermediate, "leaf")
        val gitDirectSkill = Path(project, "skills/git-direct")
        val gitAgentsSkill = Path(project, ".agents/skills/git-agents")
        val intermediateSkill = Path(intermediate, ".agents/skills/intermediate")
        val cwdDirectSkill = Path(cwd, "skills/cwd-direct")
        val cwdAgentsSkill = Path(cwd, ".agents/skills/cwd-agents")
        val userSkill = Path(agentsHome, "skills/user-skill")
        val systemSkill = Path(agentsHome, "skills/.system/system-skill")
        val kodexSkill = Path(kodexHome, "skills/kodex-skill")
        val kodexSystemSkill = Path(kodexHome, "skills/.system/kodex-system-skill")
        listOf(
            Path(project, ".git"),
            intermediate,
            cwd,
            gitDirectSkill,
            gitAgentsSkill,
            intermediateSkill,
            cwdDirectSkill,
            cwdAgentsSkill,
            userSkill,
            systemSkill,
            kodexSkill,
            kodexSystemSkill,
        ).forEach {
            SystemCoroutineFileSystem.createDirectories(it)
        }
        try {
            writeSkill(gitDirectSkill, "git-direct", "Git direct description", "git direct body")
            writeSkill(gitAgentsSkill, "duplicate", "Git Agents description", "git agents body")
            writeSkill(intermediateSkill, "intermediate", "Ignored intermediate description", "intermediate body")
            writeSkill(cwdDirectSkill, "cwd-direct", "Cwd direct description", "cwd direct body")
            writeSkill(cwdAgentsSkill, "cwd-agents", "Cwd Agents description", "cwd agents body")
            writeSkill(userSkill, "duplicate", "User description", "user body")
            writeSkill(systemSkill, "system", "System description", "system body")
            writeSkill(kodexSkill, "kodex", "Kodex description", "kodex body")
            writeSkill(kodexSystemSkill, "kodex-system", "Kodex system description", "kodex system body")
            SystemCoroutineFileSystem.writeString(Path(gitAgentsSkill, "reference.txt"), "resource")
            val resolver = fileSystemSkillsResolver(agentsHome, kodexHome)
            val resolved = resolver.resolve(cwd)

            assertEquals(
                listOf(
                    SkillScope.Repo,
                    SkillScope.Repo,
                    SkillScope.Repo,
                    SkillScope.Repo,
                    SkillScope.User,
                    SkillScope.User,
                ),
                resolved.skills.map { skill -> skill.source.scope },
            )
            assertEquals(2, resolved.skills.count { skill -> skill.name == "duplicate" })
            assertTrue(
                setOf(
                    "git-direct",
                    "duplicate",
                    "cwd-direct",
                    "cwd-agents",
                    "kodex",
                ).all { name -> resolved.skills.any { skill -> skill.name == name } },
            )
            assertTrue(resolved.skills.none { skill -> skill.name == "intermediate" })
            assertTrue(resolved.skills.none { skill -> skill.name in setOf("system", "kodex-system") })

            val repo = resolved.skills.first { skill ->
                skill.path == SystemCoroutineFileSystem.resolve(Path(gitAgentsSkill, "SKILL.md"))
            }
            val document = assertIs<SkillResourceResult.Success<*>>(resolved.loadSkill(repo)).value
            assertTrue(document.toString().contains("git agents body"))
            val resource = assertIs<SkillResourceResult.Success<*>>(
                resolved.readResource(repo, Path("reference.txt")),
            ).value
            assertEquals("resource", (resource as ByteArray).decodeToString())
            assertIs<SkillResourceResult.Failure>(resolved.readResource(repo, Path("../outside.txt")))

            writeSkill(gitAgentsSkill, "duplicate", "Updated Git Agents description", "updated body")
            assertEquals(
                "Updated Git Agents description",
                resolver.resolve(cwd).skills.first { skill ->
                    skill.path == SystemCoroutineFileSystem.resolve(Path(gitAgentsSkill, "SKILL.md"))
                }.description,
            )
        } finally {
            deleteRecursively(root)
        }
    }

    test("uses only cwd Skill roots when no Git root exists") {
        val root = temporaryDirectory("skills-without-git")
        val agentsHome = Path(root, "agents-home")
        val cwd = Path(root, "working")
        val directSkill = Path(cwd, "skills/direct")
        val agentsSkill = Path(cwd, ".agents/skills/agents")
        listOf(agentsHome, directSkill, agentsSkill).forEach { path ->
            SystemCoroutineFileSystem.createDirectories(path)
        }
        try {
            writeSkill(directSkill, "direct", "Direct description", "direct body")
            writeSkill(agentsSkill, "agents", "Agents description", "agents body")

            val resolved = fileSystemSkillsResolver(agentsHome).resolve(cwd)

            assertEquals(
                listOf("agents", "direct"),
                resolved.skills.map { skill -> skill.name },
            )
            assertTrue(resolved.skills.all { skill -> skill.source.scope == SkillScope.Repo })
        } finally {
            deleteRecursively(root)
        }
    }

    test("re-enumerates additions deletions and renames for every resolve") {
        val root = temporaryDirectory("skills-reenumeration")
        val home = Path(root, "home")
        val project = Path(root, "project")
        val skills = Path(project, ".agents/skills")
        val first = Path(skills, "first")
        val renamed = Path(skills, "renamed")
        val second = Path(skills, "second")
        listOf(Path(project, ".git"), home, first).forEach { path ->
            SystemCoroutineFileSystem.createDirectories(path)
        }
        try {
            writeSkill(first, "first", "First description", "first body")
            val resolver = fileSystemSkillsResolver(Path(home, ".agents"))
            assertEquals(listOf("first"), resolver.resolve(project).skills.map { it.name })

            SystemCoroutineFileSystem.atomicMove(first, renamed)
            SystemCoroutineFileSystem.createDirectories(second)
            writeSkill(second, "second", "Second description", "second body")
            assertEquals(
                listOf("first", "second"),
                resolver.resolve(project).skills.map { it.name },
            )
            assertEquals("renamed", resolver.resolve(project).skills.first().path.parent?.name)

            deleteRecursively(renamed)
            assertEquals(listOf("second"), resolver.resolve(project).skills.map { it.name })
        } finally {
            deleteRecursively(root)
        }
    }

    test("reuses parsed metadata while fingerprints remain unchanged") {
        val root = temporaryDirectory("skills-cache")
        val home = Path(root, "home")
        val project = Path(root, "project")
        val skill = Path(project, ".agents/skills/cached")
        listOf(Path(project, ".git"), home, skill).forEach { path ->
            SystemCoroutineFileSystem.createDirectories(path)
        }
        try {
            writeSkill(skill, "cached", "Cached description", "body")
            val fileSystem = CountingFileSystem(SystemCoroutineFileSystem)
            val resolver = fileSystemSkillsResolver(
                agentsHome = Path(home, ".agents"),
                fileSystem = fileSystem,
            )

            resolver.resolve(project)
            resolver.resolve(project)

            assertEquals(1, fileSystem.skillSourceOpenCount)
        } finally {
            deleteRecursively(root)
        }
    }

    test("uses file keys to detect same-time same-size atomic replacement") {
        val root = temporaryDirectory("skills-file-key")
        val home = Path(root, "home")
        val project = Path(root, "project")
        val skill = Path(project, ".agents/skills/replaced")
        val skillFile = Path(skill, "SKILL.md")
        val replacement = Path(skill, "SKILL.next")
        listOf(Path(project, ".git"), home, skill).forEach { path ->
            SystemCoroutineFileSystem.createDirectories(path)
        }
        try {
            val initial = skillContents("replaced", "Version alpha", "alpha")
            val updated = skillContents("replaced", "Version bravo", "bravo")
            assertEquals(initial.encodeToByteArray().size, updated.encodeToByteArray().size)
            SystemCoroutineFileSystem.writeString(skillFile, initial)
            val fileSystem = FixedTimestampFileSystem(SystemCoroutineFileSystem)
            val resolver = fileSystemSkillsResolver(
                agentsHome = Path(home, ".agents"),
                fileSystem = fileSystem,
            )
            val before = fileSystem.fingerprintOrNull(skillFile)
            assertEquals("Version alpha", resolver.resolve(project).skills.single().description)

            SystemCoroutineFileSystem.writeString(replacement, updated)
            SystemCoroutineFileSystem.atomicMove(replacement, skillFile)
            val after = fileSystem.fingerprintOrNull(skillFile)

            assertEquals(before?.size, after?.size)
            assertEquals(before?.lastModifiedAtNanoseconds, after?.lastModifiedAtNanoseconds)
            assertNotEquals(before?.fileKey, after?.fileKey)
            assertEquals("Version bravo", resolver.resolve(project).skills.single().description)
        } finally {
            deleteRecursively(root)
        }
    }

    test("reports malformed skill metadata without hiding valid skills") {
        val root = temporaryDirectory("skills-warning")
        val home = Path(root, "home")
        val project = Path(root, "project")
        val valid = Path(project, ".agents/skills/valid")
        val invalid = Path(project, ".agents/skills/invalid")
        listOf(Path(project, ".git"), home, valid, invalid).forEach { path ->
            SystemCoroutineFileSystem.createDirectories(path)
        }
        try {
            writeSkill(valid, "valid", "Valid description", "body")
            SystemCoroutineFileSystem.writeString(Path(invalid, "SKILL.md"), "no frontmatter")
            val resolver = fileSystemSkillsResolver(Path(home, ".agents"))
            val resolved = resolver.resolve(project)

            assertEquals(listOf("valid"), resolved.skills.map { it.name })
            assertEquals(1, resolved.warnings.size)
            assertTrue(resolved.warnings.single().message.contains("frontmatter"))
        } finally {
            deleteRecursively(root)
        }
    }

    test("observes Agents home changes from context settings") {
        val root = temporaryDirectory("skills-context-settings")
        val firstAgentsHome = Path(root, "first-agents-home")
        val secondAgentsHome = Path(root, "second-agents-home")
        val cwd = Path(root, "project")
        val firstSkill = Path(firstAgentsHome, "skills/first")
        val secondSkill = Path(secondAgentsHome, "skills/second")
        listOf(cwd, firstSkill, secondSkill).forEach { path ->
            SystemCoroutineFileSystem.createDirectories(path)
        }
        try {
            writeSkill(firstSkill, "first", "First Agents home", "first body")
            writeSkill(secondSkill, "second", "Second Agents home", "second body")
            val contextSettings = MutableStateFlow(testContextSettings(firstAgentsHome))
            val resolver = FileSystemSkillsResolver(
                contextSettings = contextSettings,
            )

            assertEquals(listOf("first"), resolver.resolve(cwd).skills.map { skill -> skill.name })

            contextSettings.value = testContextSettings(secondAgentsHome)

            assertEquals(listOf("second"), resolver.resolve(cwd).skills.map { skill -> skill.name })
        } finally {
            deleteRecursively(root)
        }
    }
}

private class CountingFileSystem(
    private val delegate: CoroutineFileSystem,
) : CoroutineFileSystem by delegate {
    var skillSourceOpenCount: Int = 0
        private set

    override suspend fun <R> useSource(
        path: Path,
        block: suspend (CoroutineRawSource) -> R,
    ): R {
        if (path.name == "SKILL.md") skillSourceOpenCount += 1
        return delegate.useSource(path, block)
    }
}

private class FixedTimestampFileSystem(
    private val delegate: CoroutineFileSystem,
) : CoroutineFileSystem by delegate {
    override suspend fun fingerprintOrNull(path: Path): FileFingerprint? =
        delegate.fingerprintOrNull(path)?.copy(lastModifiedAtNanoseconds = 0L)
}

private suspend fun writeSkill(
    directory: Path,
    name: String,
    description: String,
    body: String,
) {
    SystemCoroutineFileSystem.writeString(Path(directory, "SKILL.md"), skillContents(name, description, body))
}

private fun skillContents(name: String, description: String, body: String): String =
    """
    ---
    name: $name
    description: $description
    ---
    $body
    """.trimIndent()

private fun temporaryDirectory(name: String): Path =
    Path(SystemTemporaryDirectory, "kodex-$name-${Random.nextLong()}")

private fun fileSystemSkillsResolver(
    agentsHome: Path,
    kodexHome: Path = Path(agentsHome, "kodex-home"),
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): FileSystemSkillsResolver = FileSystemSkillsResolver(
    contextSettings = MutableStateFlow(testContextSettings(agentsHome, kodexHome)),
    fileSystem = fileSystem,
)

private fun testContextSettings(
    agentsHome: Path,
    kodexHome: Path = Path(agentsHome, "kodex-home"),
): AgentContextSettings = TestAgentContextSettings(agentsHome, kodexHome)

private data class TestAgentContextSettings(
    override val agentsHome: Path,
    override val kodexHome: Path,
) : AgentContextSettings {
    override val shell: Shell = TestShell
}

private val TestShell: Shell = Shell(ShellType.Sh, Path("/bin/sh"))

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { child -> deleteRecursively(child) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
