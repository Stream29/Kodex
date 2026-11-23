package io.github.stream29.kodex.app.migration

import io.github.stream29.kodex.agentstorage.sqlite.requireKodexHomeDatabase
import io.github.stream29.kodex.app.migration.v0_8_0.requireRecentHomeSourceSchema
import io.github.stream29.kodex.app.migration.v0_8_0.upgradeRecentHomeDatabase
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import io.github.stream29.kodex.utils.sqlite.openExistingSqliteConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlin.uuid.Uuid

internal suspend fun verifyRecentHomeDatabase(
    home: Path,
    source: MigrationVersion,
    fileSystem: CoroutineFileSystem,
) {
    require(source == MigrationVersion("0.7.3") || source == MigrationVersion("0.7.5"))
    // The marker and main SQLite header already passed inspectHome. Do not
    // open the live database here: even a read can recover it or write sidecars.
    requireUpgradeFile(home, Path(home, HomeDatabaseFile), fileSystem, required = true)
    for (suffix in DatabaseSidecars) {
        requireUpgradeFile(home, Path(home, "$HomeDatabaseFile$suffix"), fileSystem, required = false)
    }
}

/**
 * Prepares a recoverable backup and a converted candidate under the write
 * lease. Until both are complete, never open or alter the source SQLite files.
 */
internal suspend fun prepareRecentHomeDatabase(
    home: Path,
    source: MigrationVersion,
    fileSystem: CoroutineFileSystem,
): String {
    val attempt = Uuid.random().toString()
    val directory = upgradeDirectory(home, attempt)
    fileSystem.createDirectories(directory, mustCreate = true)
    try {
        val backup = Path(directory, BackupFile)
        val staged = Path(directory, StagedFile)
        copyRecentSnapshot(home, backup, fileSystem)
        withContext(Dispatchers.IO) {
            openExistingSqliteConnection(backup).use { connection ->
                requireRecentHomeSourceSchema(connection)
                makeStandalone(connection)
            }
        }
        requireNoSidecars(backup, fileSystem)
        fileSystem.writePrivateBytes(staged, byteArrayOf(), mustCreate = true)
        withContext(Dispatchers.IO) {
            openExistingSqliteConnection(staged).use { connection ->
                makeStandalone(connection)
                upgradeRecentHomeDatabase(connection, backup)
                requireKodexHomeDatabase(connection)
            }
        }
        requireNoSidecars(staged, fileSystem)
        fileSystem.writePrivateString(
            Path(directory, PreparedFile), preparationRecord(source, attempt), mustCreate = true,
        )
        return attempt
    } catch (failure: Throwable) {
        try {
            withContext(NonCancellable) {
                removeOwnedAttempt(directory, fileSystem)
            }
        } catch (cleanup: Throwable) {
            if (cleanup !== failure) failure.addSuppressed(cleanup)
        }
        if (failure is CancellationException || failure !is Exception) throw failure
        throw incompatibleHome(home, "Recent Home upgrade was not applied: ${failure.message ?: "validation failed"}", failure)
    }
}

/** The caller must first prove that the Home marker does not reference this attempt. */
internal suspend fun discardPreparedRecentHomeDatabase(
    home: Path,
    source: MigrationVersion,
    attempt: String,
    fileSystem: CoroutineFileSystem,
) {
    val directory = upgradeDirectory(home, attempt)
    requirePreparedAttempt(home, directory, source, attempt, fileSystem)
    removeOwnedAttempt(directory, fileSystem)
}

/**
 * Publishes only the staged database named by the persistent upgrading marker.
 * After the atomic move, the missing staged file and the validated modern
 * live database are the recovery witness; the original backup remains.
 */
internal suspend fun migrateRecentHomeDatabase(
    home: Path,
    source: MigrationVersion,
    attempt: String,
    fileSystem: CoroutineFileSystem,
) = inspectHomeRead(home, "Unable to publish the prepared recent Home upgrade") {
    val directory = upgradeDirectory(home, attempt)
    requirePreparedAttempt(home, directory, source, attempt, fileSystem)
    val backup = Path(directory, BackupFile)
    requireUpgradeFile(home, backup, fileSystem, required = true)
    requireNoSidecars(backup, fileSystem)
    withContext(Dispatchers.IO) {
        openExistingSqliteConnection(backup).use(::requireRecentHomeSourceSchema)
    }

    val live = Path(home, HomeDatabaseFile)
    val staged = Path(directory, StagedFile)
    if (!requireUpgradeFile(home, staged, fileSystem, required = false)) {
        requireNoSidecars(staged, fileSystem)
        requireRecentUpgradeTargetHeader(home, fileSystem)
        requireHomeDatabaseSchema(home)
        return@inspectHomeRead
    }
    requireNoSidecars(staged, fileSystem)
    withContext(Dispatchers.IO) {
        openExistingSqliteConnection(staged).use(::requireKodexHomeDatabase)
        // Checkpoint the old live database through SQLite and leave DELETE
        // mode before replacing it. Never delete a live WAL directly.
        openExistingSqliteConnection(live).use { connection ->
            requireRecentHomeSourceSchema(connection)
            makeStandalone(connection)
        }
    }
    requireNoSidecars(live, fileSystem)
    fileSystem.atomicMove(staged, live)
}

private suspend fun requirePreparedAttempt(
    home: Path,
    directory: Path,
    source: MigrationVersion,
    attempt: String,
    fileSystem: CoroutineFileSystem,
) {
    check(fileSystem.metadataOrNull(directory)?.isDirectory == true && fileSystem.isDirectEntry(directory)) {
        "The upgrade attempt directory is missing or is not a direct directory."
    }
    val stamp = Path(directory, PreparedFile)
    requireUpgradeFile(home, stamp, fileSystem, required = true)
    check(fileSystem.readBytes(stamp, 128).decodeToString() == preparationRecord(source, attempt)) {
        "The upgrade attempt does not match its preparation record."
    }
}

private suspend fun removeOwnedAttempt(directory: Path, fileSystem: CoroutineFileSystem) {
    for (name in listOf(BackupFile, StagedFile)) {
        for (suffix in DatabaseSidecars) fileSystem.delete(Path(directory, name + suffix), mustExist = false)
        fileSystem.delete(Path(directory, name), mustExist = false)
    }
    fileSystem.delete(Path(directory, PreparedFile), mustExist = false)
    fileSystem.delete(directory)
}

private suspend fun copyRecentSnapshot(home: Path, backup: Path, fileSystem: CoroutineFileSystem) {
    // SQLite rebuilds SHM from WAL. Both the main file and durable companions
    // are copied under exclusive ownership, and any recovery touches this copy.
    val suffixes = listOf("", "-wal", "-journal")
    val files = suffixes.map { suffix -> Path(home, HomeDatabaseFile + suffix) }
    val before = files.map { fileSystem.fingerprintOrNull(it) }
    for (index in files.indices) {
        val required = index == 0
        if (!requireUpgradeFile(home, files[index], fileSystem, required)) continue
        val target = checkNotNull(backup.parent).let { Path(it, BackupFile + suffixes[index]) }
        fileSystem.useSource(files[index]) { input ->
            fileSystem.useSink(target, mustCreate = true) { output ->
                fileSystem.protectPrivateFile(target)
                val buffer = Buffer()
                while (true) {
                    val count = input.readAtMostTo(buffer, 128 * 1024L)
                    if (count == -1L) break
                    check(count > 0) { "A SQLite snapshot read made no progress." }
                    output.write(buffer, count)
                }
                output.flush()
            }
        }
    }
    check(before == files.map { fileSystem.fingerprintOrNull(it) }) {
        "The SQLite source changed while taking a snapshot."
    }
}

private fun makeStandalone(connection: SqliteConnection) {
    connection.execute("PRAGMA synchronous=FULL")
    val checkpoint = connection.query("PRAGMA wal_checkpoint(TRUNCATE)").single()
    check(checkpoint.size == 3 && checkpoint[0] == 0L && checkpoint[1] == checkpoint[2]) {
        "The SQLite WAL could not be fully checkpointed."
    }
    check(connection.query("PRAGMA journal_mode=DELETE") == listOf(listOf("delete"))) {
        "The SQLite snapshot could not leave WAL mode."
    }
}

private suspend fun requireNoSidecars(database: Path, fileSystem: CoroutineFileSystem) {
    val directory = checkNotNull(database.parent)
    val names = fileSystem.list(directory).map { it.name }.toSet()
    check(DatabaseSidecars.none { database.name + it in names }) {
        "SQLite sidecars are still present for ${database.name}."
    }
}

private suspend fun requireUpgradeFile(
    home: Path,
    path: Path,
    fileSystem: CoroutineFileSystem,
    required: Boolean,
): Boolean {
    val metadata = fileSystem.metadataOrNull(path)
    if (metadata == null && !required && path !in fileSystem.list(checkNotNull(path.parent))) return false
    if (metadata?.isRegularFile != true || !fileSystem.isDirectEntry(path)) {
        throw incompatibleHome(home, "Missing or unsafe recent Home SQLite entry: $path")
    }
    return true
}

private fun upgradeDirectory(home: Path, attempt: String): Path {
    require(Uuid.parse(attempt).toString() == attempt) { "Invalid recent Home upgrade attempt." }
    return Path(home, ".kodex-upgrade-0.8.0-$attempt")
}

private fun preparationRecord(source: MigrationVersion, attempt: String): String =
    "kodex-recent-home-0.8.0\nsource=$source\nattempt=$attempt\n"

private const val BackupFile = "original.db"
private const val StagedFile = "prepared.db"
private const val PreparedFile = "prepared"
private val DatabaseSidecars = listOf("-wal", "-shm", "-journal")
