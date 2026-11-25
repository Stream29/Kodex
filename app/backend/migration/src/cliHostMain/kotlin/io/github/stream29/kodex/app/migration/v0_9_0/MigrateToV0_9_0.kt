package io.github.stream29.kodex.app.migration.v0_9_0

import io.github.stream29.kodex.app.migration.isDirectEntry
import io.github.stream29.kodex.app.migration.requireHomeDatabaseHeader
import io.github.stream29.kodex.app.migration.requireHomeDatabaseSchema
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.io.files.Path

private const val SessionsDirectoryName = "sessions"
private const val AgentsDirectoryName = "agents"
private const val SkillsDirectoryName = "skills"
private const val LegacyHomeSkillDirectoryName = "kodex-home"
private const val SkillFileName = "SKILL.md"
private const val ArchiveMarkerFileName = "archive.mark"
private const val LatestPointerFileName = "latest.json"
private const val JsonSuffix = ".json"

// Frozen mirror timeline directory names written by Kodex v0.7.0–v0.8.1.
private val MirrorTimelineNames = setOf(
    "index",
    "work",
    "settings",
    "timestamp",
    "token-count",
    "unstable",
    "steer",
)

/**
 * Retires the legacy `$KODEX_HOME/sessions/` JSON file mirror after verifying that
 * `kodex.db` is a valid generation-1 SQLite database.
 *
 * Deletes only known mirror files (`<canonical-int>.json`, `latest.json`, and root
 * `archive.mark`) inside canonical non-negative integer session/agent directories and
 * removes directories that become empty as a result. Unknown files, unknown directories
 * (including legacy `subagents/`), and symbolic links are preserved untouched.
 */
internal suspend fun migrateToV0_9_0(home: Path, fileSystem: CoroutineFileSystem) {
    retireSessionFileMirrors(home, fileSystem)
    retireLegacyKodexHomeSkill(home, fileSystem)
}

internal suspend fun retireLegacyKodexHomeSkill(home: Path, fileSystem: CoroutineFileSystem) {
    val skillsDir = Path(home, SkillsDirectoryName)
    if (!isDirectDirectory(skillsDir, fileSystem)) return

    val legacyDir = Path(skillsDir, LegacyHomeSkillDirectoryName)
    if (!isDirectDirectory(legacyDir, fileSystem)) return

    val legacySkill = Path(legacyDir, SkillFileName)
    if (isDirectRegularFile(legacySkill, fileSystem)) {
        fileSystem.delete(legacySkill, mustExist = false)
    }
    deleteDirectoryIfEmpty(legacyDir, fileSystem)
}

internal suspend fun retireSessionFileMirrors(home: Path, fileSystem: CoroutineFileSystem) {
    // Verify the SQLite source of truth before touching or enumerating sessions/.
    requireHomeDatabaseHeader(home, fileSystem)
    requireHomeDatabaseSchema(home)

    val sessionsDir = Path(home, SessionsDirectoryName)
    if (!isDirectDirectory(sessionsDir, fileSystem)) return

    for (sessionEntry in fileSystem.list(sessionsDir)) {
        if (!isCanonicalNonNegativeInt(sessionEntry.name)) continue
        if (!isDirectDirectory(sessionEntry, fileSystem)) continue
        cleanRootSessionDirectory(sessionEntry, fileSystem)
        deleteDirectoryIfEmpty(sessionEntry, fileSystem)
    }

    deleteDirectoryIfEmpty(sessionsDir, fileSystem)
}

private suspend fun cleanRootSessionDirectory(sessionDir: Path, fileSystem: CoroutineFileSystem) {
    val archiveMark = Path(sessionDir, ArchiveMarkerFileName)
    if (isDirectRegularFile(archiveMark, fileSystem)) {
        fileSystem.delete(archiveMark, mustExist = false)
    }

    cleanTimelineDirectories(sessionDir, fileSystem)

    val agentsDir = Path(sessionDir, AgentsDirectoryName)
    if (isDirectDirectory(agentsDir, fileSystem)) {
        for (agentEntry in fileSystem.list(agentsDir)) {
            if (!isCanonicalNonNegativeInt(agentEntry.name)) continue
            if (!isDirectDirectory(agentEntry, fileSystem)) continue
            cleanTimelineDirectories(agentEntry, fileSystem)
            deleteDirectoryIfEmpty(agentEntry, fileSystem)
        }
        deleteDirectoryIfEmpty(agentsDir, fileSystem)
    }
}

private suspend fun cleanTimelineDirectories(ownerDir: Path, fileSystem: CoroutineFileSystem) {
    for (timelineName in MirrorTimelineNames) {
        val timelineDir = Path(ownerDir, timelineName)
        if (!isDirectDirectory(timelineDir, fileSystem)) continue
        for (entry in fileSystem.list(timelineDir)) {
            if (!isMirrorRecordFileName(entry.name)) continue
            if (!isDirectRegularFile(entry, fileSystem)) continue
            fileSystem.delete(entry, mustExist = false)
        }
        deleteDirectoryIfEmpty(timelineDir, fileSystem)
    }
}

private suspend fun isDirectDirectory(path: Path, fileSystem: CoroutineFileSystem): Boolean {
    val metadata = fileSystem.metadataOrNull(path) ?: return false
    return metadata.isDirectory && fileSystem.isDirectEntry(path)
}

private suspend fun isDirectRegularFile(path: Path, fileSystem: CoroutineFileSystem): Boolean {
    val metadata = fileSystem.metadataOrNull(path) ?: return false
    return metadata.isRegularFile && fileSystem.isDirectEntry(path)
}

private suspend fun deleteDirectoryIfEmpty(directory: Path, fileSystem: CoroutineFileSystem) {
    if (!isDirectDirectory(directory, fileSystem)) return
    if (fileSystem.list(directory).isEmpty()) {
        fileSystem.delete(directory, mustExist = false)
    }
}

private fun isMirrorRecordFileName(name: String): Boolean {
    if (name == LatestPointerFileName) return true
    if (!name.endsWith(JsonSuffix)) return false
    val stem = name.removeSuffix(JsonSuffix)
    return isCanonicalNonNegativeInt(stem)
}

private fun isCanonicalNonNegativeInt(value: String): Boolean {
    if (value.isEmpty()) return false
    if (value.length > 1 && value[0] == '0') return false
    if (!value.all { it in '0'..'9' }) return false
    return value.toIntOrNull() != null
}
