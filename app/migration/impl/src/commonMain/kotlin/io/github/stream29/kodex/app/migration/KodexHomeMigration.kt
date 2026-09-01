package io.github.stream29.kodex.app.migration

import io.github.stream29.kodex.agentstorage.filesystemlayout.latestIndexPath
import io.github.stream29.kodex.agentstorage.filesystemlayout.readLatestIndex
import io.github.stream29.kodex.agentstorage.filesystemlayout.requireStorageLayout
import io.github.stream29.kodex.agentstorage.filesystemlayout.storedRecordIndexes
import io.github.stream29.kodex.agentstorage.filesystemlayout.timelineDirectory
import io.github.stream29.kodex.utils.filesystemlease.FileSystemLease
import io.github.stream29.kodex.utils.filesystemlease.FileSystemReadLease
import io.github.stream29.kodex.utils.filesystemlease.FileSystemWriteLease
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path

public class KodexHomeHandle internal constructor(
    public val home: Path,
    public val version: MigrationVersion,
    private val lease: FileSystemLease,
) : AutoCloseable {
    override fun close() {
        lease.close()
    }

    public suspend fun closeAndJoin() {
        lease.close()
        lease.coroutineContext.job.join()
    }
}

public val CurrentKodexApplicationVersion: MigrationVersion by lazy {
    MigrationVersion(GeneratedKodexApplicationVersion)
}

public suspend fun CoroutineScope.prepareKodexHome(
    home: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): KodexHomeHandle = prepareKodexHome(
    home = home,
    currentVersion = CurrentKodexApplicationVersion,
    migrations = KodexHomeMigrations,
    fileSystem = fileSystem,
)

internal suspend fun CoroutineScope.prepareKodexHome(
    home: Path,
    currentVersion: MigrationVersion,
    migrations: List<Migration>,
    fileSystem: CoroutineFileSystem,
): KodexHomeHandle {
    validateRegistry(migrations)
    val lockDirectory = Path(home, LocksDirectory, HomeLockDirectory)
    var readLease: FileSystemLease? =
        FileSystemReadLease(lockDirectory, fileSystem)
    try {
        val storedVersion = readVersionOrNull(home, fileSystem)
        if (storedVersion != null) {
            if (storedVersion > currentVersion) {
                throw KodexHomeVersionException(
                    "Kodex Home version $storedVersion is newer than this application " +
                        "version $currentVersion.",
                )
            }
            if (storedVersion == currentVersion) {
                val retainedLease = checkNotNull(readLease)
                readLease = null
                return KodexHomeHandle(home, currentVersion, retainedLease)
            }
        }
    } finally {
        withContext(NonCancellable) {
            readLease?.let { lease ->
                lease.close()
                lease.coroutineContext.job.join()
            }
        }
    }

    val writeLease = FileSystemWriteLease(lockDirectory, fileSystem)
    try {
        prepareUnderWriteLease(home, currentVersion, migrations, fileSystem)
    } finally {
        withContext(NonCancellable) {
            writeLease.close()
            writeLease.coroutineContext.job.join()
        }
    }

    val finalReadLease = FileSystemReadLease(lockDirectory, fileSystem)
    try {
        val preparedVersion = readVersionOrNull(home, fileSystem)
            ?: throw KodexHomeVersionException("Kodex Home version disappeared after preparation.")
        if (preparedVersion != currentVersion) {
            throw KodexHomeVersionException(
                "Kodex Home changed to version $preparedVersion while starting $currentVersion.",
            )
        }
        return KodexHomeHandle(home, currentVersion, finalReadLease)
    } catch (failure: Throwable) {
        withContext(NonCancellable) {
            finalReadLease.close()
            finalReadLease.coroutineContext.job.join()
        }
        throw failure
    }
}

private suspend fun prepareUnderWriteLease(
    home: Path,
    currentVersion: MigrationVersion,
    migrations: List<Migration>,
    fileSystem: CoroutineFileSystem,
) {
    val existingVersion = readVersionOrNull(home, fileSystem)
    val wasUnversioned = existingVersion == null
    var storedVersion = existingVersion ?: run {
        validateUnversionedHome(home, fileSystem)
        UnversionedBaseline
    }
    if (storedVersion > currentVersion) {
        throw KodexHomeVersionException(
            "Kodex Home version $storedVersion is newer than this application version $currentVersion.",
        )
    }
    for (migration in migrations) {
        if (storedVersion >= migration.toVersion || migration.toVersion > currentVersion) continue
        migration.action(home, fileSystem)
        writeVersion(home, migration.toVersion, fileSystem)
        storedVersion = migration.toVersion
    }
    if (wasUnversioned || storedVersion != currentVersion) {
        writeVersion(home, currentVersion, fileSystem)
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

public open class KodexHomeVersionException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

public class KodexHomeLayoutException(
    message: String,
    cause: Throwable? = null,
) : KodexHomeVersionException(message, cause)

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
