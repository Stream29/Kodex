package io.github.stream29.kodex.app.migration.v0_3_5

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

internal suspend fun migrateToV0_3_5(
    home: Path,
    fileSystem: CoroutineFileSystem,
) {
    val skillDirectory = Path(home, SkillsDirectory, SkillName)
    val target = Path(skillDirectory, SkillFileName)
    val temporary = Path(skillDirectory, TemporaryFileName)

    withContext(NonCancellable) {
        fileSystem.delete(temporary, mustExist = false)
    }
    try {
        fileSystem.createDirectories(skillDirectory)
        fileSystem.writeString(
            path = temporary,
            content = KodexHomeSkill,
            mustCreate = true,
        )
        fileSystem.atomicMove(temporary, target)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } finally {
        withContext(NonCancellable) {
            fileSystem.delete(temporary, mustExist = false)
        }
    }
}

private const val SkillsDirectory: String = "skills"
private const val SkillName: String = "kodex-home"
private const val SkillFileName: String = "SKILL.md"
private const val TemporaryFileName: String = ".kodex-migration-0.3.5-skill.tmp"
