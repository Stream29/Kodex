package io.github.stream29.kodex.app.migration

import io.github.stream29.kodex.agentstorage.filesystemlayout.latestIndexPath
import io.github.stream29.kodex.agentstorage.filesystemlayout.readLatestIndex
import io.github.stream29.kodex.agentstorage.filesystemlayout.requireStorageLayout
import io.github.stream29.kodex.agentstorage.filesystemlayout.storedRecordIndexes
import io.github.stream29.kodex.agentstorage.filesystemlayout.timelineDirectory
import io.github.stream29.kodex.utils.filesystemlease.FileSystemLease
import io.github.stream29.kodex.utils.filesystemlease.FileSystemLeaseInUseException
import io.github.stream29.kodex.utils.filesystemlease.FileSystemReadLease
import io.github.stream29.kodex.utils.filesystemlease.FileSystemWriteLease
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlin.time.Duration.Companion.seconds

internal class HeldLeaseKodexHomeHandle(
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
    val lockDirectory = Path(home, LocksDirectory, HomeLockDirectory)
    var readLease: FileSystemLease? =
        FileSystemReadLease(lockDirectory, fileSystem)
    var readFailure: Throwable? = null
    val storedVersion = try {
        val storedVersion = readVersionOrNull(home, fileSystem)
        if (storedVersion != null) {
            requireSupportedHomeVersion(storedVersion, currentVersion)
            if (!hasPendingMigrations(storedVersion, currentVersion, migrations)) {
                val retainedLease = checkNotNull(readLease)
                currentCoroutineContext().ensureActive()
                coroutineContext.ensureActive()
                retainedLease.coroutineContext.ensureActive()
                readLease = null
                return HeldLeaseKodexHomeHandle(home, storedVersion, retainedLease)
            }
        }
        storedVersion
    } catch (failure: Throwable) {
        readFailure = failure
        throw failure
    } finally {
        readLease?.let { closeHomeLease(it, readFailure) }
    }

    val writeLease = try {
        FileSystemWriteLease(lockDirectory, fileSystem)
    } catch (failure: FileSystemLeaseInUseException) {
        val pendingMigrations = migrations.filter {
            (storedVersion ?: UnversionedBaseline) < it.toVersion && it.toVersion <= currentVersion
        }
        val update = if (storedVersion == null) {
            "Kodex Home at $home needs initialization."
        } else {
            "Kodex Home at $home needs data migration from $storedVersion " +
                "to ${pendingMigrations.last().toVersion}."
        }
        val work = if (pendingMigrations.isNotEmpty()) {
            "Required data migration targets: ${pendingMigrations.joinToString { it.toVersion.toString() }}."
        } else {
            "The existing Home layout must be validated and its baseline version registered."
        }
        throw KodexHomeInUseException(
            "$update\n$work\n" +
                "This requires exclusive access, but another Kodex instance is using this Home. " +
                "Close all other Kodex instances using this Home, then retry.\n" +
                "Lease details: ${failure.message}",
            failure,
        )
    }
    var writeFailure: Throwable? = null
    try {
        prepareUnderWriteLease(
            home = home,
            currentVersion = currentVersion,
            migrations = migrations,
            fileSystem = fileSystem,
            onMigrationStarted = onMigrationStarted,
        )
    } catch (failure: Throwable) {
        writeFailure = failure
        throw failure
    } finally {
        closeHomeLease(writeLease, writeFailure)
    }

    val finalReadLease = FileSystemReadLease(lockDirectory, fileSystem)
    try {
        val preparedVersion = readVersionOrNull(home, fileSystem)
            ?: throw KodexHomeVersionException("Kodex Home version disappeared after preparation.")
        requireSupportedHomeVersion(preparedVersion, currentVersion)
        if (hasPendingMigrations(preparedVersion, currentVersion, migrations)) {
            throw KodexHomeVersionException(
                "Kodex Home version $preparedVersion still requires data migration " +
                    "for application version $currentVersion after preparation.",
            )
        }
        currentCoroutineContext().ensureActive()
        coroutineContext.ensureActive()
        finalReadLease.coroutineContext.ensureActive()
        return HeldLeaseKodexHomeHandle(home, preparedVersion, finalReadLease)
    } catch (failure: Throwable) {
        closeHomeLease(finalReadLease, failure)
        throw failure
    }
}

private fun requireSupportedHomeVersion(
    storedVersion: MigrationVersion,
    currentVersion: MigrationVersion,
) {
    if (storedVersion > currentVersion) {
        throw KodexHomeVersionException(
            "Kodex Home version $storedVersion is newer than this application version $currentVersion.",
        )
    }
}

private fun hasPendingMigrations(
    storedVersion: MigrationVersion,
    currentVersion: MigrationVersion,
    migrations: List<Migration>,
): Boolean = migrations.any { storedVersion < it.toVersion && it.toVersion <= currentVersion }

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

private suspend fun prepareUnderWriteLease(
    home: Path,
    currentVersion: MigrationVersion,
    migrations: List<Migration>,
    fileSystem: CoroutineFileSystem,
    onMigrationStarted: (fromVersion: MigrationVersion, toVersion: MigrationVersion) -> Unit,
) {
    val existingVersion = readVersionOrNull(home, fileSystem)
    var storedVersion = existingVersion ?: UnversionedBaseline
    requireSupportedHomeVersion(storedVersion, currentVersion)
    if (existingVersion == null) {
        validateUnversionedHome(home, fileSystem)
        writeVersion(home, storedVersion, fileSystem)
    }
    for (migration in migrations) {
        if (storedVersion >= migration.toVersion || migration.toVersion > currentVersion) continue
        onMigrationStarted(storedVersion, migration.toVersion)
        migration.action(home, fileSystem)
        writeVersion(home, migration.toVersion, fileSystem)
        storedVersion = migration.toVersion
    }
}

private suspend fun validateUnversionedHome(
    home: Path,
    fileSystem: CoroutineFileSystem,
) {
    val sessions = Path(home, SessionsDirectory)
    val metadata = fileSystem.metadataOrNull(sessions) ?: return
    if (!metadata.isDirectory) {
        throw KodexHomeLayoutException("Sessions path is not a directory: $sessions")
    }
    for (entry in fileSystem.list(sessions)) {
        val name = entry.name
        if (name.startsWith('.')) continue
        val index = name.toCanonicalIndexOrNull()
            ?: throw KodexHomeLayoutException("Invalid Session entry: ${Path(sessions, name)}")
        val session = Path(sessions, index.toString())
        if (fileSystem.metadataOrNull(session)?.isDirectory != true) {
            throw KodexHomeLayoutException("Session entry is not a directory: $session")
        }
        try {
            requireStorageLayout(session, CurrentTimelineNames, fileSystem)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            throw KodexHomeLayoutException("Invalid Session storage layout: $session", failure)
        }
        for (timelineName in CurrentTimelineNames) {
            val timeline = timelineDirectory(session, timelineName)
            val indexes = storedRecordIndexes(timeline, fileSystem)
            val expectedLatest = indexes.lastOrNull() ?: EmptyIndex
            val actualLatest = try {
                readLatestIndex(timeline, fileSystem)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                throw KodexHomeLayoutException(
                    "Invalid latest pointer: ${latestIndexPath(timeline)}",
                    failure,
                )
            }
            if (actualLatest != expectedLatest) {
                throw KodexHomeLayoutException(
                    "Latest pointer ${latestIndexPath(timeline)} is $actualLatest, " +
                        "but numbered records end at $expectedLatest.",
                )
            }
        }
    }
}

private suspend fun readVersionOrNull(
    home: Path,
    fileSystem: CoroutineFileSystem,
): MigrationVersion? {
    val path = Path(home, VersionFileName)
    val metadata = fileSystem.metadataOrNull(path) ?: return null
    if (!metadata.isRegularFile) {
        throw KodexHomeVersionException("Kodex Home version is not a regular file: $path")
    }
    val encoded = try {
        fileSystem.readBytes(path, MaxVersionFileBytes).decodeToString()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        throw KodexHomeVersionException("Unable to read Kodex Home version: $path", failure)
    }
    val value = decodeJsonString(encoded)
        ?: throw KodexHomeVersionException("Kodex Home version must be one JSON string: $path")
    return try {
        MigrationVersion(value)
    } catch (failure: IllegalArgumentException) {
        throw KodexHomeVersionException("Invalid Kodex Home migration version in $path: $value", failure)
    }
}

private suspend fun writeVersion(
    home: Path,
    version: MigrationVersion,
    fileSystem: CoroutineFileSystem,
) {
    fileSystem.createDirectories(home)
    fileSystem.writeString(Path(home, VersionFileName), "\"$version\"")
}

private fun validateRegistry(migrations: List<Migration>) {
    var previous: MigrationVersion? = null
    migrations.forEach { migration ->
        if (previous != null && migration.toVersion <= previous) {
            throw IllegalStateException(
                "Kodex Home migrations must be unique and strictly ordered: " +
                    "$previous, ${migration.toVersion}",
            )
        }
        previous = migration.toVersion
    }
}

private fun decodeJsonString(encoded: String): String? {
    val trimmed = encoded.trim()
    if (trimmed.length < 2 || trimmed.first() != '"' || trimmed.last() != '"') return null
    val value = trimmed.substring(1, trimmed.lastIndex)
    if (value.any { character -> character == '"' || character == '\\' || character.isISOControl() }) {
        return null
    }
    return value
}

private fun String.toCanonicalIndexOrNull(): Int? {
    val index = toIntOrNull()?.takeIf { it >= 0 } ?: return null
    return index.takeIf { it.toString() == this }
}

private val UnversionedBaseline: MigrationVersion = MigrationVersion("0.3.2")
private val CurrentTimelineNames: Set<String> = setOf(
    "index",
    "work",
    "settings",
    "timestamp",
    "token-count",
    "unstable",
)
private const val SessionsDirectory: String = "sessions"
private const val LocksDirectory: String = ".locks"
private const val HomeLockDirectory: String = "home"
private const val VersionFileName: String = "version.json"
private const val EmptyIndex: Int = -1
private const val MaxVersionFileBytes: Long = 4_096
