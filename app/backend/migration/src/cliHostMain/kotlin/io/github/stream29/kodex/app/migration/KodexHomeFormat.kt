package io.github.stream29.kodex.app.migration

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

internal sealed interface HomeFormat {
    data object Fresh : HomeFormat
    data class Initializing(val databasePublished: Boolean) : HomeFormat
    data class Ready(val checkpoint: MigrationVersion) : HomeFormat
}

internal suspend fun inspectHome(
    home: Path,
    currentVersion: MigrationVersion,
    fileSystem: CoroutineFileSystem,
): HomeFormat = inspectHomeRead(home, "Unable to inspect Kodex Home") {
    val root = fileSystem.metadataOrNull(home) ?: return@inspectHomeRead HomeFormat.Fresh
    if (!root.isDirectory) throw invalidHomeLayout(home, "Home is not a directory")

    val versionPath = Path(home, HomeVersionFile)
    val version = fileSystem.metadataOrNull(versionPath)
    if (version == null) {
        requireStartupLayout(home, fileSystem, initializing = false)
        return@inspectHomeRead HomeFormat.Fresh
    }
    if (!version.isRegularFile || !fileSystem.isDirectEntry(versionPath)) {
        throw incompatibleHome(home, "The version marker is not a regular file: $versionPath")
    }
    val marker = try {
        parseHomeMarker(fileSystem.readBytes(versionPath, MaxVersionFileBytes).decodeToString(throwOnInvalidSequence = true))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw incompatibleHome(home, "Unreadable or unsupported version marker: $versionPath", failure)
    }
    if (marker.checkpoint > currentVersion) {
        throw incompatibleHome(home, "Checkpoint ${marker.checkpoint} is newer than application $currentVersion")
    }
    if (!marker.initializing) {
        // The first check never opens SQLite or touches its WAL/SHM. The ready fast
        // path does not enumerate business data or apply the fresh layout restrictions.
        if (requireDirectoryIfPresent(home, Path(home, ".locks"), fileSystem)) {
            val backend = Path(home, ".locks", "backend")
            if (requireDirectoryIfPresent(home, backend, fileSystem)) {
                requireStartupFile(home, Path(backend, "instance.lock"), fileSystem)
            }
            val leases = homeLeaseDirectory(home)
            if (requireDirectoryIfPresent(home, leases, fileSystem)) requireHomeLeaseFiles(home, leases, fileSystem)
        }
        requireHomeDatabaseHeader(home, fileSystem)
        return@inspectHomeRead HomeFormat.Ready(marker.checkpoint)
    }

    if (marker.checkpoint != currentVersion) {
        throw incompatibleHome(
            home,
            "Initialization was interrupted by application ${marker.checkpoint}; " +
                "finish it with that version before reopening with $currentVersion",
        )
    }
    val published = requireStartupLayout(home, fileSystem, initializing = true)
    if (published) requireHomeDatabaseHeader(home, fileSystem, requireStandalone = true)
    HomeFormat.Initializing(databasePublished = published)
}

private data class HomeMarker(val checkpoint: MigrationVersion, val initializing: Boolean)

private fun parseHomeMarker(encoded: String): HomeMarker {
    // This envelope contains only simple tokens. Reading the fields explicitly
    // lets us reject duplicates, escapes and unknown fields rather than decoding
    // them as aliases of the supported format.
    val objectText = encoded.trim(::isJsonWhitespace)
    require(objectText.startsWith('{') && objectText.endsWith('}'))
    val members = mutableMapOf<String, String>()
    for (part in objectText.substring(1, objectText.lastIndex).split(',')) {
        val member = MarkerMember.matchEntire(part) ?: error("Invalid marker member")
        val (name, token) = member.destructured
        require(members.put(name, token) == null) { "Duplicate marker member: $name" }
    }
    require(members.remove("generation") == HomeGeneration.toString()) { "Unsupported Home generation" }
    val checkpoint = requireNotNull(members.remove("checkpoint")) { "Missing checkpoint" }
    require(checkpoint.startsWith('"') && checkpoint.endsWith('"')) { "Expected a string checkpoint" }
    val initializing = members.remove("state")?.let { state ->
        require(state == "\"initializing\"") { "Unknown initialization state" }
        true
    } ?: false
    require(members.isEmpty()) { "Unknown marker members" }
    return HomeMarker(MigrationVersion(checkpoint.substring(1, checkpoint.lastIndex)), initializing)
}

internal suspend fun writeHomeMarker(
    home: Path,
    checkpoint: MigrationVersion,
    fileSystem: CoroutineFileSystem,
    initializing: Boolean = false,
) {
    val temporary = Path(home, MarkerTemporaryFile)
    fileSystem.metadataOrNull(temporary)?.let { metadata ->
        if (!metadata.isRegularFile || !fileSystem.isDirectEntry(temporary)) {
            throw incompatibleHome(home, "Marker temporary is not a regular file: $temporary")
        }
        // An interrupted write cannot have changed the published marker.
        fileSystem.delete(temporary)
    }
    val status = if (initializing) ",\"state\":\"initializing\"" else ""
    var primary: Throwable? = null
    try {
        fileSystem.writeString(
            temporary, """{"generation":$HomeGeneration,"checkpoint":"$checkpoint"$status}""", mustCreate = true,
        )
        fileSystem.atomicMove(temporary, Path(home, HomeVersionFile))
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        try {
            withContext(NonCancellable) { fileSystem.delete(temporary, mustExist = false) }
        } catch (cleanup: Throwable) {
            if (primary == null) throw cleanup
            if (cleanup !== primary) primary.addSuppressed(cleanup)
        }
    }
}

private suspend fun requireStartupLayout(
    home: Path,
    fileSystem: CoroutineFileSystem,
    initializing: Boolean,
): Boolean {
    var published = false
    var hasStaging = false
    for (entry in fileSystem.list(home)) {
        when {
            entry.name == ".locks" -> requireStartupLocks(home, entry, fileSystem)
            entry.name == MarkerTemporaryFile ||
                (initializing && entry.name == HomeVersionFile) -> requireStartupFile(home, entry, fileSystem)
            initializing && entry.name == HomeDatabaseFile -> {
                requireStartupFile(home, entry, fileSystem)
                published = true
            }
            initializing && entry.name in StagingDatabaseFiles -> {
                requireStartupFile(home, entry, fileSystem)
                hasStaging = true
            }
            else -> throw invalidHomeLayout(home, "Unexpected entry in an unprepared Home: $entry")
        }
    }
    if (published && hasStaging) {
        throw invalidHomeLayout(home, "Both staging and published databases are present during initialization")
    }
    return published
}

private suspend fun requireStartupLocks(home: Path, locks: Path, fileSystem: CoroutineFileSystem) {
    if (!requireDirectoryIfPresent(home, locks, fileSystem)) return
    for (directory in fileSystem.list(locks)) {
        when (directory.name) {
            "home" -> {
                if (requireDirectoryIfPresent(home, directory, fileSystem)) requireHomeLeaseFiles(home, directory, fileSystem)
            }
            "backend" -> {
                if (!requireDirectoryIfPresent(home, directory, fileSystem)) continue
                for (entry in fileSystem.list(directory)) {
                    if (entry.name != "instance.lock") throw invalidHomeLayout(home, "Unexpected startup lock entry: $entry")
                    requireStartupFile(home, entry, fileSystem)
                }
            }
            else -> throw invalidHomeLayout(home, "Unexpected startup lock directory: $directory")
        }
    }
}

private suspend fun requireHomeLeaseFiles(home: Path, directory: Path, fileSystem: CoroutineFileSystem) {
    for (entry in fileSystem.list(directory)) {
        if (entry.name != "guard.lock" && !HomeLeaseFile.matches(entry.name)) {
            throw invalidHomeLayout(home, "Unexpected Home lease entry: $entry")
        }
        requireStartupFile(home, entry, fileSystem)
    }
}

private suspend fun requireStartupFile(home: Path, path: Path, fileSystem: CoroutineFileSystem) {
    // Leases publish and remove recognized entries concurrently during read-only
    // preflight. An entry that has already disappeared won't become trusted data.
    val metadata = fileSystem.metadataOrNull(path)
    if (metadata == null) {
        if (path in fileSystem.list(checkNotNull(path.parent))) {
            throw invalidHomeLayout(home, "Unreadable or non-regular startup entry: $path")
        }
        return
    }
    if (!metadata.isRegularFile || !fileSystem.isDirectEntry(path)) {
        if (fileSystem.metadataOrNull(path) == null && path !in fileSystem.list(checkNotNull(path.parent))) return
        throw invalidHomeLayout(home, "Startup entry is not a regular file: $path")
    }
}

private suspend fun requireDirectoryIfPresent(
    home: Path, directory: Path, fileSystem: CoroutineFileSystem,
): Boolean {
    val metadata = fileSystem.metadataOrNull(directory)
    if (metadata == null) {
        // Do not treat dangling symbolic links as absent owned directories.
        val parent = checkNotNull(directory.parent)
        if (fileSystem.metadataOrNull(parent)?.isDirectory == true && directory in fileSystem.list(parent)) {
            throw invalidHomeLayout(home, "Unreadable or non-directory startup path: $directory")
        }
        return false
    } else if (!metadata.isDirectory || !fileSystem.isDirectEntry(directory)) {
        throw invalidHomeLayout(home, "Startup path is not a directory: $directory")
    }
    return true
}

internal suspend fun CoroutineFileSystem.isDirectEntry(path: Path): Boolean {
    val resolved = try {
        resolve(path)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: kotlinx.io.IOException) {
        return false
    }
    return resolved == Path(resolve(checkNotNull(path.parent)), path.name)
}

internal fun homeLeaseDirectory(home: Path): Path = Path(home, ".locks", "home")

internal fun incompatibleHome(home: Path, reason: String, cause: Throwable? = null): KodexHomeVersionException =
    KodexHomeVersionException(homeRejectionMessage(home, reason), cause)

private fun invalidHomeLayout(home: Path, reason: String): KodexHomeLayoutException =
    KodexHomeLayoutException(homeRejectionMessage(home, reason))

private fun homeRejectionMessage(home: Path, reason: String): String =
    "Cannot use Kodex Home at $home: $reason. Use a different, empty Kodex Home; leave the existing Home intact."

internal suspend inline fun <T> inspectHomeRead(home: Path, reason: String, block: () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (rejected: KodexHomeVersionException) {
    throw rejected
} catch (failure: Exception) {
    throw incompatibleHome(home, reason, failure)
}

private fun isJsonWhitespace(character: Char): Boolean =
    character == ' ' || character == '\t' || character == '\r' || character == '\n'

internal const val HomeVersionFile: String = "version.json"
private const val HomeGeneration: Int = 1
private const val MarkerTemporaryFile: String = ".kodex-version.json.tmp"
private const val MaxVersionFileBytes: Long = 4_096
private val MarkerMember = Regex("""[ \t\r\n]*"([a-z]+)"[ \t\r\n]*:[ \t\r\n]*([0-9]+|"[a-z0-9.]+")[ \t\r\n]*""")
private const val LeaseUuid = "[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
private val HomeLeaseFile = Regex("""(?:[1-9][0-9]*\.(?:read|write)\.lock|\.lock-$LeaseUuid\.tmp|\.stale-lock-$LeaseUuid\.json)""")
