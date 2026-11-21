package io.github.stream29.kodex.app.migration

import io.github.stream29.kodex.utils.filesystemlease.FileSystemLease
import io.github.stream29.kodex.utils.filesystemlease.FileSystemLeaseInUseException
import io.github.stream29.kodex.utils.filesystemlease.FileSystemReadLease
import io.github.stream29.kodex.utils.filesystemlease.FileSystemWriteLease
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlin.time.Duration.Companion.seconds

private class HeldLeaseKodexHomeHandle(
    override val home: Path,
    override val version: MigrationVersion,
    private val lease: FileSystemLease,
) : KodexHomeHandle {
    override fun close() {
        lease.close()
    }

    override suspend fun closeAndJoin() {
        lease.closeAndJoin()
    }
}

public actual val CurrentKodexApplicationVersion: MigrationVersion by lazy {
    MigrationVersion(GeneratedKodexApplicationVersion)
}

public actual suspend fun preflightKodexHome(home: Path, fileSystem: CoroutineFileSystem): Unit {
    inspectHome(home, CurrentKodexApplicationVersion, fileSystem)
}

public actual suspend fun CoroutineScope.prepareKodexHome(
    home: Path,
    fileSystem: CoroutineFileSystem,
    onMigrationStarted: (fromVersion: MigrationVersion, toVersion: MigrationVersion) -> Unit,
): KodexHomeHandle = prepareKodexHome(
    home = home,
    currentVersion = CurrentKodexApplicationVersion,
    migrations = KodexHomeMigrations,
    fileSystem = fileSystem,
    onMigrationStarted = onMigrationStarted,
)

internal suspend fun CoroutineScope.prepareKodexHome(
    home: Path,
    currentVersion: MigrationVersion,
    migrations: List<Migration>,
    fileSystem: CoroutineFileSystem,
    onMigrationStarted: (fromVersion: MigrationVersion, toVersion: MigrationVersion) -> Unit =
        { _, _ -> },
): KodexHomeHandle {
    validateRegistry(migrations)
    // Leases create directories and owner files. Refuse old and unrecognized
    // Homes before acquiring even the shared lease, then verify under each lease.
    inspectHome(home, currentVersion, fileSystem)
    val lockDirectory = homeLeaseDirectory(home)
    var readLease: FileSystemLease? = FileSystemReadLease(lockDirectory, fileSystem)
    var readFailure: Throwable? = null
    val work = try {
        val format = inspectHome(home, currentVersion, fileSystem)
        val pending = if (format is HomeFormat.Ready) applicableMigrations(format.checkpoint, currentVersion, migrations)
            else emptyList()
        if (format is HomeFormat.Ready && pending.isEmpty()) {
            requireHomeDatabaseSchema(home)
            val handle = retainReadLease(home, format.checkpoint, checkNotNull(readLease))
            readLease = null
            return handle
        }
        format to pending
    } catch (failure: Throwable) {
        readFailure = failure
        throw failure
    } finally {
        readLease?.let { closeHomeLease(it, readFailure) }
    }

    val writeLease = try {
        FileSystemWriteLease(lockDirectory, fileSystem)
    } catch (failure: FileSystemLeaseInUseException) {
        val (format, pending) = work
        val update = if (format is HomeFormat.Ready) {
            "Kodex Home at $home needs data migration from ${format.checkpoint} to ${pending.last().toVersion}.\n" +
                "Required data migration targets: ${pending.joinToString { it.toVersion.toString() }}."
        } else {
            "Kodex Home at $home needs initialization."
        }
        throw KodexHomeInUseException(
            "$update\n" +
                "This requires exclusive access, but another Kodex instance is using this Home. " +
                "Close all other Kodex instances using this Home, then retry.\n" +
                "Lease details: ${failure.message}",
            failure,
        )
    }
    var writeFailure: Throwable? = null
    try {
        when (val format = inspectHome(home, currentVersion, fileSystem)) {
            HomeFormat.Fresh -> {
                writeHomeMarker(home, currentVersion, fileSystem, initializing = true)
                initializeHomeDatabase(home, fileSystem)
                writeHomeMarker(home, currentVersion, fileSystem)
            }
            is HomeFormat.Initializing -> {
                if (format.databasePublished) requireHomeDatabaseSchema(home, requireEmpty = true)
                else initializeHomeDatabase(home, fileSystem)
                writeHomeMarker(home, currentVersion, fileSystem)
            }
            is HomeFormat.Ready -> {
                val pending = applicableMigrations(format.checkpoint, currentVersion, migrations)
                var checkpoint = format.checkpoint
                for ((index, migration) in pending.withIndex()) {
                    onMigrationStarted(checkpoint, migration.toVersion)
                    migration.action(home, fileSystem)
                    if (index == pending.lastIndex) {
                        // An older same-generation schema is a valid migration
                        // input. Validate the latest schema only at the last target.
                        requireHomeDatabaseHeader(home, fileSystem)
                        requireHomeDatabaseSchema(home)
                    }
                    writeHomeMarker(home, migration.toVersion, fileSystem)
                    checkpoint = migration.toVersion
                }
            }
        }
    } catch (failure: Throwable) {
        writeFailure = failure
        throw failure
    } finally {
        closeHomeLease(writeLease, writeFailure)
    }

    val finalReadLease = FileSystemReadLease(lockDirectory, fileSystem)
    try {
        val format = inspectHome(home, currentVersion, fileSystem)
        if (format !is HomeFormat.Ready) {
            throw incompatibleHome(home, "The ready marker is missing after preparation")
        }
        if (applicableMigrations(format.checkpoint, currentVersion, migrations).isNotEmpty()) {
            throw incompatibleHome(home, "Checkpoint ${format.checkpoint} still requires migration after preparation")
        }
        requireHomeDatabaseSchema(home)
        return retainReadLease(home, format.checkpoint, finalReadLease)
    } catch (failure: Throwable) {
        closeHomeLease(finalReadLease, failure)
        throw failure
    }
}

private suspend fun CoroutineScope.retainReadLease(
    home: Path, checkpoint: MigrationVersion, lease: FileSystemLease,
): KodexHomeHandle {
    currentCoroutineContext().ensureActive()
    coroutineContext.ensureActive()
    lease.coroutineContext.ensureActive()
    return HeldLeaseKodexHomeHandle(home, checkpoint, lease)
}

private fun applicableMigrations(
    checkpoint: MigrationVersion,
    currentVersion: MigrationVersion,
    migrations: List<Migration>,
): List<Migration> = migrations.filter { checkpoint < it.toVersion && it.toVersion <= currentVersion }

private suspend fun closeHomeLease(lease: FileSystemLease, primary: Throwable? = null) {
    val cleanup = withContext(NonCancellable) {
        withContext(Dispatchers.Default) {
            try {
                withTimeout(30.seconds) { runCatching { lease.closeAndJoin() } }
            } catch (failure: Throwable) {
                Result.failure<Unit>(failure)
            }
        }
    }.exceptionOrNull() ?: return
    if (primary == null) throw cleanup
    if (primary !== cleanup && primary.suppressedExceptions.none { it === cleanup }) {
        primary.addSuppressed(cleanup)
    }
}

private fun validateRegistry(migrations: List<Migration>) {
    for ((previous, next) in migrations.zipWithNext()) {
        check(previous.toVersion < next.toVersion) {
            "Kodex Home migrations must be unique and strictly ordered: ${previous.toVersion}, ${next.toVersion}"
        }
    }
}
