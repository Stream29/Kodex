package io.github.stream29.kodex.app.migration.v0_4_3

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

internal suspend fun migrateToV0_4_3(
    home: Path,
    fileSystem: CoroutineFileSystem,
) {
    val skillDirectory = Path(home, "skills", "kodex-home")
    val target = Path(skillDirectory, "SKILL.md")
    val temporary = Path(skillDirectory, ".kodex-migration-0.4.3-skill.tmp")

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
    } finally {
        withContext(NonCancellable) {
            fileSystem.delete(temporary, mustExist = false)
        }
    }
}
