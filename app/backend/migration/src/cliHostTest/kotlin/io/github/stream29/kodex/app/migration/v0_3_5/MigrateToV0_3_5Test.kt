package io.github.stream29.kodex.app.migration.v0_3_5

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

val migrateToV0_3_5Test by testSuite {
    testFixture {
        Path(SystemTemporaryDirectory, "kodex-migration-0.3.5-${Random.nextLong()}").also {
            SystemCoroutineFileSystem.createDirectories(it)
        }
    } closeWith {
        deleteRecursively(this)
    } asParameterForEach {
        test("installs the frozen skill and preserves neighboring files") { home ->
            val skillDirectory = Path(home, "skills", "kodex-home")
            val otherSkill = Path(home, "skills", "other", "SKILL.md")
            val target = Path(skillDirectory, "SKILL.md")
            val temporary = Path(skillDirectory, ".kodex-migration-0.3.5-skill.tmp")
            SystemCoroutineFileSystem.createDirectories(otherSkill.parent!!)
            SystemCoroutineFileSystem.createDirectories(skillDirectory)
            SystemCoroutineFileSystem.writeString(otherSkill, "other")
            SystemCoroutineFileSystem.writeString(target, "old")
            SystemCoroutineFileSystem.writeString(temporary, "stale")

            migrateToV0_3_5(home, SystemCoroutineFileSystem)

            assertEquals(KodexHomeSkill, SystemCoroutineFileSystem.readString(target))
            assertEquals("other", SystemCoroutineFileSystem.readString(otherSkill))
            assertFalse(SystemCoroutineFileSystem.exists(temporary))
        }

        test("replaces an existing product skill on every invocation") { home ->
            val target = Path(home, "skills", "kodex-home", "SKILL.md")
            SystemCoroutineFileSystem.createDirectories(target.parent!!)
            SystemCoroutineFileSystem.writeString(target, "modified")

            migrateToV0_3_5(home, SystemCoroutineFileSystem)
            migrateToV0_3_5(home, SystemCoroutineFileSystem)

            assertEquals(KodexHomeSkill, SystemCoroutineFileSystem.readString(target))
        }

        test("fails without replacing data when the skill root is not a directory") { home ->
            val skills = Path(home, "skills")
            SystemCoroutineFileSystem.writeString(skills, "not a directory")

            assertFailsWith<Throwable> {
                migrateToV0_3_5(home, SystemCoroutineFileSystem)
            }
            assertTrue(SystemCoroutineFileSystem.exists(skills))
            assertFalse(SystemCoroutineFileSystem.exists(Path(home, "skills", "kodex-home", "SKILL.md")))
        }
    }
}

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { child -> deleteRecursively(child) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
