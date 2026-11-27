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
    val format = inspectHome(home, CurrentKodexApplicationVersion, fileSystem)
    val source = format.recentUpgradeSource() ?: return
    val action = if (format is HomeFormat.Upgrading) "resume its upgrade" else "upgrade it"
    throw KodexHomeVersionException(
        "Kodex Home at $home has a recent legacy checkpoint $source. " +
            "Close other Kodex instances using this Home, then run 'kodex backend start' to $action.",
    )
}

public actual suspend fun preflightKodexHomeStartup(home: Path, fileSystem: CoroutineFileSystem): Unit {
    inspectHomeForStartup(home, CurrentKodexApplicationVersion, fileSystem)
}

private suspend fun inspectHomeForStartup(
    home: Path,
    currentVersion: MigrationVersion,
    fileSystem: CoroutineFileSystem,
): HomeFormat {
    val format = inspectHome(home, currentVersion, fileSystem)
    if (format is HomeFormat.RecentLegacy) {
        inspectHomeRead(home, "Unable to verify the recent Kodex Home database") {
            verifyRecentHomeDatabase(home, format.source, fileSystem)
        }
    }
    return format
}

private fun HomeFormat.recentUpgradeSource(): MigrationVersion? = when (this) {
    is HomeFormat.RecentLegacy -> source
    is HomeFormat.Upgrading -> source
    else -> null
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
    // Leases create directories and owner files. Reject unsupported Homes first.
    // A recent Home needs an exclusive lease, never a temporary shared owner.
    val initialFormat = inspectHomeForStartup(home, currentVersion, fileSystem)
    val lockDirectory = homeLeaseDirectory(home)
    val work: Pair<HomeFormat, List<Migration>> = if (initialFormat.recentUpgradeSource() != null) {
        initialFormat to emptyList()
    } else {
        var readLease: FileSystemLease? = FileSystemReadLease(lockDirectory, fileSystem)
        var readFailure: Throwable? = null
        try {
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
    }

    val writeLease = try {
        FileSystemWriteLease(lockDirectory, fileSystem)
    } catch (failure: FileSystemLeaseInUseException) {
        val (format, pending) = work
        val source = format.recentUpgradeSource()
        val update = when {
            source != null -> "Kodex Home at $home needs a data upgrade from $source to $RecentHomeUpgradeTarget."
            format is HomeFormat.Ready ->
                "Kodex Home at $home needs data migration from ${format.checkpoint} to ${pending.last().toVersion}.\n" +
                    "Required data migration targets: ${pending.joinToString { it.toVersion.toString() }}."
            else -> "Kodex Home at $home needs initialization."
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
        val format = inspectHome(home, currentVersion, fileSystem)
        if (work.first.recentUpgradeSource() != null && !format.continuesRecentUpgrade(work.first)) {
            throw incompatibleHome(home, "The Home upgrade state changed while acquiring exclusive access")
        }
        when (format) {
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
                applyHomeMigrations(home, format.checkpoint, currentVersion, migrations, fileSystem, onMigrationStarted)
            }
            is HomeFormat.RecentLegacy, is HomeFormat.Upgrading -> {
                val source = checkNotNull(format.recentUpgradeSource())
                onMigrationStarted(source, RecentHomeUpgradeTarget)
                val attempt = if (format is HomeFormat.Upgrading) {
                    format.attempt
                } else {
                    // Back up and validate the staged target before fencing old clients.
                    val preparedAttempt = prepareRecentHomeDatabase(home, source, fileSystem)
                    try {
                        if (inspectHome(home, currentVersion, fileSystem) != format) {
                            throw incompatibleHome(home, "The Home upgrade state changed while staging the database")
                        }
                        writeLease.coroutineContext.ensureActive()
                        writeUpgradingHomeMarker(home, source, preparedAttempt, fileSystem)
                    } catch (failure: Throwable) {
                        try {
                            withContext(NonCancellable) {
                                // An exception need not imply that the marker
                                // was not published. Keep any referenced attempt.
                                if (inspectHome(home, currentVersion, fileSystem) == format) {
                                    discardPreparedRecentHomeDatabase(home, source, preparedAttempt, fileSystem)
                                }
                            }
                        } catch (cleanup: Throwable) {
                            if (cleanup !== failure) failure.addSuppressed(cleanup)
                        }
                        throw failure
                    }
                    preparedAttempt
                }
                writeLease.coroutineContext.ensureActive()
                migrateRecentHomeDatabase(home, source, attempt, fileSystem)
                requireRecentUpgradeTargetHeader(home, fileSystem)
                writeLease.coroutineContext.ensureActive()
                writeHomeMarker(home, RecentHomeUpgradeTarget, fileSystem)
                applyHomeMigrations(home, RecentHomeUpgradeTarget, currentVersion, migrations, fileSystem, onMigrationStarted)
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

private fun HomeFormat.continuesRecentUpgrade(previous: HomeFormat): Boolean = when (this) {
    is HomeFormat.RecentLegacy -> this == previous
    is HomeFormat.Upgrading -> when (previous) {
        is HomeFormat.RecentLegacy -> source == previous.source
        is HomeFormat.Upgrading -> this == previous
        else -> false
    }
    is HomeFormat.Ready -> checkpoint >= RecentHomeUpgradeTarget
    else -> false
}

private suspend fun applyHomeMigrations(
    home: Path,
    storedCheckpoint: MigrationVersion,
    currentVersion: MigrationVersion,
    migrations: List<Migration>,
    fileSystem: CoroutineFileSystem,
    onMigrationStarted: (MigrationVersion, MigrationVersion) -> Unit,
) {
    val pending = applicableMigrations(storedCheckpoint, currentVersion, migrations)
    var checkpoint = storedCheckpoint
    for ((index, migration) in pending.withIndex()) {
        onMigrationStarted(checkpoint, migration.toVersion)
        migration.action(home, fileSystem)
        if (index == pending.lastIndex) {
            // A same-generation migration may start from an older schema.
            requireHomeDatabaseHeader(home, fileSystem)
            requireHomeDatabaseSchema(home)
        }
        writeHomeMarker(home, migration.toVersion, fileSystem)
        checkpoint = migration.toVersion
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
