package io.github.stream29.codex.lite.agentcontext.skill.filesystem

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentContextSettings
import io.github.stream29.codex.lite.agentcontext.skill.contract.SkillResourceResult
import io.github.stream29.codex.lite.agentcontext.prefix.skill.contract.SkillScope
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.CoroutineRawSource
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.FileFingerprint
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.codex.lite.utils.shellclient.Shell
import io.github.stream29.codex.lite.utils.shellclient.ShellType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

val fileSystemSkillsResolverTest by testSuite {
    test("keeps same-named skills and preserves source precedence") {
        val root = temporaryDirectory("skills-catalog")
        val codexHome = Path(root, "codex-home")
        val userHome = Path(root, "home")
        val project = Path(root, "project")
        val cwd = Path(project, "module")
        val repoSkill = Path(project, ".agents/skills/repo-skill")
        val userSkill = Path(userHome, ".agents/skills/user-skill")
        val systemSkill = Path(codexHome, "skills/.system/system-skill")
        listOf(Path(project, ".git"), cwd, repoSkill, userSkill, systemSkill).forEach {
            SystemCoroutineFileSystem.createDirectories(it)
        }
        try {
            writeSkill(repoSkill, "duplicate", "Repo description", "repo body")
            writeSkill(userSkill, "duplicate", "User description", "user body")
            writeSkill(systemSkill, "system", "System description", "system body")
            SystemCoroutineFileSystem.writeString(Path(repoSkill, "reference.txt"), "resource")
            val resolver = fileSystemSkillsResolver(codexHome, userHome)
            val resolved = resolver.resolve(cwd)

            assertEquals(
                listOf(SkillScope.Repo, SkillScope.User, SkillScope.System),
                resolved.skills.map { skill -> skill.source.scope },
            )
            assertEquals(listOf("duplicate", "duplicate", "system"), resolved.skills.map { it.name })

            val repo = resolved.skills.first()
            val document = assertIs<SkillResourceResult.Success<*>>(resolved.loadSkill(repo)).value
            assertTrue(document.toString().contains("repo body"))
            val resource = assertIs<SkillResourceResult.Success<*>>(
                resolved.readResource(repo, Path("reference.txt")),
            ).value
            assertEquals("resource", (resource as ByteArray).decodeToString())
            assertIs<SkillResourceResult.Failure>(resolved.readResource(repo, Path("../outside.txt")))

            writeSkill(repoSkill, "duplicate", "Updated repo description", "updated body")
            assertEquals(
                "Updated repo description",
                resolver.resolve(cwd).skills.first().description,
            )
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
            val resolver = fileSystemSkillsResolver(Path(home, ".codex"), home)
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
                codexHome = Path(home, ".codex"),
                userHome = home,
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
                codexHome = Path(home, ".codex"),
                userHome = home,
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
            val resolver = fileSystemSkillsResolver(Path(home, ".codex"), home)
            val resolved = resolver.resolve(project)

            assertEquals(listOf("valid"), resolved.skills.map { it.name })
            assertEquals(1, resolved.warnings.size)
            assertTrue(resolved.warnings.single().message.contains("frontmatter"))
        } finally {
            deleteRecursively(root)
        }
    }

    test("observes codex home changes from context settings") {
        val root = temporaryDirectory("skills-context-settings")
        val userHome = Path(root, "home")
        val firstCodexHome = Path(root, "first-codex-home")
        val secondCodexHome = Path(root, "second-codex-home")
        val cwd = Path(root, "project")
        val firstSkill = Path(firstCodexHome, "skills/first")
        val secondSkill = Path(secondCodexHome, "skills/second")
        listOf(userHome, cwd, firstSkill, secondSkill).forEach { path ->
            SystemCoroutineFileSystem.createDirectories(path)
        }
        try {
            writeSkill(firstSkill, "first", "First Codex home", "first body")
            writeSkill(secondSkill, "second", "Second Codex home", "second body")
            val contextSettings = MutableStateFlow(testContextSettings(firstCodexHome))
            val resolver = FileSystemSkillsResolver(
                contextSettings = contextSettings,
                userHome = userHome,
            )

            assertEquals(listOf("first"), resolver.resolve(cwd).skills.map { skill -> skill.name })

            contextSettings.value = testContextSettings(secondCodexHome)

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

    override suspend fun source(path: Path): CoroutineRawSource {
        if (path.name == "SKILL.md") skillSourceOpenCount += 1
        return delegate.source(path)
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
    Path(SystemTemporaryDirectory, "codex-lite-$name-${Random.nextLong()}")

private fun fileSystemSkillsResolver(
    codexHome: Path,
    userHome: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): FileSystemSkillsResolver = FileSystemSkillsResolver(
    contextSettings = MutableStateFlow(testContextSettings(codexHome)),
    userHome = userHome,
    fileSystem = fileSystem,
)

private fun testContextSettings(codexHome: Path): AgentContextSettings =
    TestAgentContextSettings(codexHome)

private data class TestAgentContextSettings(
    override val codexHome: Path,
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
