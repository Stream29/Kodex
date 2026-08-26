package io.github.stream29.kodex.agentsession.filesystem

import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexAgentDependencies
import io.github.stream29.kodex.agentsession.contract.KodexRootSessionEntry
import io.github.stream29.kodex.agentsession.contract.KodexRootSessionRepository
import io.github.stream29.kodex.agentsession.contract.KodexSessionEntry
import io.github.stream29.kodex.agentstorage.filesystem.FileSystemAgentStorage
import io.github.stream29.kodex.agentstorage.filesystem.FileSystemIndexVersioned
import io.github.stream29.kodex.agentstorage.filesystem.forkRangeRawTo
import io.github.stream29.kodex.agentstorage.filesystem.ofEmpty
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.forkRangeTo
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.filesystemlease.FileSystemLease
import io.github.stream29.kodex.utils.filesystemlease.FileSystemLeaseInUseException
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Filesystem-backed recursive session repository. */
public class FileSystemKodexSessionRepository internal constructor(
    scope: CoroutineScope,
    root: Path,
    private val fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    private val valueCacheSize: Int = 1_024,
    private val dependencies: KodexAgentDependencies,
    initialEntries: List<Int>,
) :
    KodexRootSessionRepository,
    CoroutineScope by scope {
    private val entriesMutex = Mutex()
    private val openRoots: MutableMap<Int, KodexAgentSession> = mutableMapOf()
    private val mutableEntries = MutableStateFlow(initialEntries)
    private val sessionsRoot = Path(root, SessionsDirectory)

    override val entries: StateFlow<List<Int>> = mutableEntries.asStateFlow()

    init {
        coroutineContext[Job]?.invokeOnCompletion { openRoots.clear() }
    }

    override suspend fun list(): List<Int> = entriesMutex.withLock {
        requireOpen()
        entries.value
    }

    override suspend fun listEntries(): List<KodexRootSessionEntry> =
        listEntries(includeArchived = true)

    override suspend fun listEntries(
        includeArchived: Boolean,
    ): List<KodexRootSessionEntry> = entriesMutex.withLock {
        requireOpen()
        entries.value.mapNotNull { entryIndex ->
            val archived = isArchived(entryIndex)
            if (archived && !includeArchived) return@mapNotNull null
            rootEntry(entryIndex, archived)
        }
    }

    override suspend fun getEntry(entryIndex: Int): KodexRootSessionEntry =
        entriesMutex.withLock {
            requireOpen()
            requireEntry(entryIndex)
            rootEntry(entryIndex, archived = isArchived(entryIndex))
        }

    private suspend fun rootEntry(
        entryIndex: Int,
        archived: Boolean,
    ): KodexRootSessionEntry {
        val entry = fileSystemRootSessionEntry(
            entryIndex = entryIndex,
            directory = sessionDirectory(entryIndex),
            fileSystem = fileSystem,
        )
        return FileSystemRootSessionEntry(
            delegate = entry,
            archived = archived,
            updateArchived = { updated ->
                updateArchiveMarker(entryIndex, updated)
            },
        )
    }

    private suspend fun updateArchiveMarker(
        entryIndex: Int,
        archived: Boolean,
    ): Unit = entriesMutex.withLock {
        requireOpen()
        requireEntry(entryIndex)
        if (archived) {
            fileSystem.writeString(
                path = archiveMarker(entryIndex),
                content = "",
            )
        } else {
            fileSystem.delete(archiveMarker(entryIndex), mustExist = false)
        }
    }

    override suspend fun create(): Int = entriesMutex.withLock {
        requireOpen()
        val (index, directory) = reserveSessionDirectory()
        FileSystemAgentStorage.ofEmpty(
            directory = directory,
            fileSystem = fileSystem,
            mustCreateDirectory = false,
        )
        fileSystem.createDirectories(Path(directory, SubagentsDirectory), mustCreate = true)
        mutableEntries.value = (entries.value + index).sorted()
        index
    }

    override suspend fun createFork(
        source: KodexAgentStorage,
        from: Int,
        until: Int,
    ): Int = entriesMutex.withLock {
        requireOpen()
        val (index, directory) = reserveSessionDirectory()
        try {
            val target = FileSystemAgentStorage.ofEmpty(
                directory = directory,
                fileSystem = fileSystem,
                mustCreateDirectory = false,
            )
            fileSystem.createDirectories(Path(directory, SubagentsDirectory), mustCreate = true)
            materializeFork(source, from, until, target)
            mutableEntries.value = (entries.value + index).sorted()
            index
        } catch (failure: Throwable) {
            withContext(NonCancellable) { deleteRecursively(directory) }
            throw failure
        }
    }

    private suspend fun reserveSessionDirectory(): Pair<Int, Path> {
        val unavailable = entries.value.toMutableSet()
        while (true) {
            val index = smallestMissing(unavailable.toList())
            val directory = sessionDirectory(index)
            try {
                fileSystem.createDirectories(directory, mustCreate = true)
            } catch (failure: IOException) {
                if (fileSystem.metadataOrNull(directory)?.isDirectory != true) throw failure
                unavailable += index
                continue
            }
            return index to directory
        }
    }

    override suspend fun open(
        entryIndex: Int,
    ): KodexAgentSession = entriesMutex.withLock {
        requireOpen()
        require(entryIndex >= 0) { "Session entry index must be non-negative." }
        openRoots[entryIndex]?.let { root ->
            if (root.coroutineContext[Job]?.isActive == true) return@withLock root
            openRoots.remove(entryIndex)
        }
        val directory = sessionDirectory(entryIndex)
        require(fileSystem.metadataOrNull(directory)?.isDirectory == true) {
            "Session directory does not exist: $directory"
        }
        if (entryIndex !in entries.value) {
            mutableEntries.value = (entries.value + entryIndex).sorted()
        }
        FileSystemKodexAgentSession(
            directory = directory,
            fileSystem = fileSystem,
            valueCacheSize = valueCacheSize,
            dependencies = dependencies,
        ).also { root ->
            openRoots[entryIndex] = root
        }
    }

    override suspend fun delete(entryIndex: Int): Unit = entriesMutex.withLock {
        requireOpen()
        require(entryIndex >= 0) { "Session entry index must be non-negative." }
        val openRoot = openRoots.remove(entryIndex)
        withContext(NonCancellable) {
            openRoot?.coroutineContext?.get(Job)?.cancelAndJoin()
        }
        val directory = sessionDirectory(entryIndex)
        val lease = acquireSessionLeaseAfterClose(entryIndex)
        try {
            deleteDirectoryContents(directory, except = LockFile)
        } finally {
            withContext(NonCancellable) {
                lease.closeAndJoin()
            }
        }
        fileSystem.delete(directory, mustExist = false)
        mutableEntries.value = entries.value - entryIndex
    }

    private suspend fun acquireSessionLeaseAfterClose(
        sessionIndex: Int,
    ): FileSystemLease {
        var delayDuration = 1.milliseconds
        while (true) {
            try {
                return FileSystemLease(
                    lockPath = Path(sessionDirectory(sessionIndex), LockFile),
                    fileSystem = fileSystem,
                    duration = SessionLeaseDuration,
                )
            } catch (_: FileSystemLeaseInUseException) {
                delay(delayDuration)
                delayDuration = (delayDuration * 2).coerceAtMost(100.milliseconds)
            }
        }
    }

    private fun sessionDirectory(index: Int): Path =
        Path(sessionsRoot, index.toString())

    private fun archiveMarker(index: Int): Path =
        Path(sessionDirectory(index), ArchiveMarkerFile)

    private suspend fun isArchived(index: Int): Boolean =
        fileSystem.exists(archiveMarker(index))

    private fun requireEntry(entryIndex: Int) {
        require(entryIndex >= 0) { "Session entry index must be non-negative." }
        require(entryIndex in entries.value) {
            "No Session entry exists at index $entryIndex."
        }
    }

    private suspend fun deleteRecursively(path: Path) {
        val metadata = fileSystem.metadataOrNull(path) ?: return
        if (metadata.isDirectory) {
            fileSystem.list(path).forEach { child -> deleteRecursively(child) }
        }
        fileSystem.delete(path, mustExist = false)
    }

    private suspend fun deleteDirectoryContents(
        directory: Path,
        except: String,
    ) {
        fileSystem.list(directory)
            .filterNot { path -> path.name == except }
            .forEach { path -> deleteRecursively(path) }
    }

    private fun requireOpen() {
        check(coroutineContext[Job]?.isActive == true) { "Session repository is closed." }
    }

}

private suspend fun materializeFork(
    source: KodexAgentStorage,
    from: Int,
    until: Int,
    target: FileSystemAgentStorage,
) {
    when (source) {
        is CachedAgentStorage -> source.backing.forkRangeRawTo(from, until, target)
        is FileSystemAgentStorage -> source.forkRangeRawTo(from, until, target)
        else -> source.forkRangeTo(from, until, target)
    }
}

private suspend fun sessionDirectories(
    directory: Path,
    fileSystem: CoroutineFileSystem,
): List<Pair<Int, Path>> =
    fileSystem.list(directory)
        .filterNot { path -> path.name.startsWith(".") }
        .map { sessionDirectory ->
            val index = sessionDirectory.name.toInt()
            require(index >= 0) { "Session index must be non-negative: $sessionDirectory" }
            require(fileSystem.metadataOrNull(sessionDirectory)?.isDirectory == true) {
                "Session entry is not a directory: $sessionDirectory"
            }
            index to sessionDirectory
        }
        .sortedBy { (index) -> index }

internal fun smallestMissing(values: List<Int>): Int {
    var candidate = 0
    values.sorted().forEach { value ->
        if (value == candidate) candidate += 1
    }
    return candidate
}

internal suspend fun childDirectories(
    directory: Path,
    fileSystem: CoroutineFileSystem,
): List<Pair<Int, Path>> =
    fileSystem.list(directory)
        .filterNot { path -> path.name.startsWith(".") }
        .mapNotNull { child ->
            val index = child.name.toIntOrNull()
                ?.takeIf { it >= 0 }
                ?: return@mapNotNull null
            if (fileSystem.metadataOrNull(child)?.isDirectory != true) return@mapNotNull null
            index to child
        }
        .sortedBy { (index) -> index }

internal suspend fun createEmptyFileSystemAgentSessionNode(
    directory: Path,
    fileSystem: CoroutineFileSystem,
) {
    FileSystemAgentStorage.ofEmpty(directory, fileSystem)
    fileSystem.createDirectories(Path(directory, SubagentsDirectory), mustCreate = true)
}

internal suspend fun deleteRecursively(
    path: Path,
    fileSystem: CoroutineFileSystem,
) {
    val metadata = fileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        fileSystem.list(path).forEach { child -> deleteRecursively(child, fileSystem) }
    }
    fileSystem.delete(path, mustExist = false)
}

private data class FileSystemSessionEntry(
    override val entryIndex: Int,
    override val threadName: String?,
    override val lastActivityAt: Instant?,
) : KodexSessionEntry

private class FileSystemRootSessionEntry(
    private val delegate: KodexSessionEntry,
    override val archived: Boolean,
    private val updateArchived: suspend (Boolean) -> Unit,
) :
    KodexRootSessionEntry,
    KodexSessionEntry by delegate {
    override suspend fun archive(): Unit = updateArchived(true)

    override suspend fun unarchive(): Unit = updateArchived(false)
}

internal suspend fun fileSystemSessionEntry(
    entryIndex: Int,
    directory: Path,
    fileSystem: CoroutineFileSystem,
): KodexSessionEntry {
    val storage = FileSystemAgentStorage(directory, fileSystem)
    val settingsIndex = storage.settings.latestIndex()
    val timestampIndex = storage.timestamp.latestIndex()
    return FileSystemSessionEntry(
        entryIndex = entryIndex,
        threadName = settingsIndex.takeIf { it >= 0 }?.let { index -> storage.settings[index].threadName },
        lastActivityAt = timestampIndex.takeIf { it >= 0 }?.let { index -> storage.timestamp[index] },
    )
}

private suspend fun CoroutineScope.fileSystemRootSessionEntry(
    entryIndex: Int,
    directory: Path,
    fileSystem: CoroutineFileSystem,
): KodexSessionEntry {
    val storage = FileSystemAgentStorage(directory, fileSystem)
    val pointedSettingsIndex = storage.settings.latestIndexFromPointerOrNull()
    val pointedTimestampIndex = storage.timestamp.latestIndexFromPointerOrNull()
    if (pointedSettingsIndex != null && pointedTimestampIndex != null) {
        return storage.sessionEntry(entryIndex, pointedSettingsIndex, pointedTimestampIndex)
    }

    val lease = tryAcquireSessionLease(directory, fileSystem)
    if (lease == null) {
        return storage.sessionEntry(
            entryIndex = entryIndex,
            settingsIndex = pointedSettingsIndex ?: storage.settings.scannedLatestIndex(),
            timestampIndex = pointedTimestampIndex ?: storage.timestamp.scannedLatestIndex(),
        )
    }
    return lease.useAndRelease {
        storage.sessionEntry(
            entryIndex = entryIndex,
            settingsIndex = storage.settings.repairedLatestIndex(),
            timestampIndex = storage.timestamp.repairedLatestIndex(),
        )
    }
}

private suspend fun CoroutineScope.tryAcquireSessionLease(
    directory: Path,
    fileSystem: CoroutineFileSystem,
): FileSystemLease? {
    val lockPath = Path(directory, LockFile)
    return try {
        FileSystemLease(
            lockPath = lockPath,
            fileSystem = fileSystem,
            duration = SessionLeaseDuration,
        )
    } catch (_: FileSystemLeaseInUseException) {
        null
    } catch (failure: IOException) {
        if (fileSystem.metadataOrNull(lockPath)?.isRegularFile == true) null else throw failure
    }
}

private suspend fun <T> FileSystemLease.useAndRelease(block: suspend () -> T): T {
    return try {
        async(start = CoroutineStart.UNDISPATCHED) { block() }.await()
    } finally {
        withContext(NonCancellable) { closeAndJoin() }
    }
}

private suspend fun <T> FileSystemIndexVersioned<T>.scannedLatestIndex(): Int =
    storedIndexes().lastOrNull() ?: -1

private suspend fun <T> FileSystemIndexVersioned<T>.repairedLatestIndex(): Int {
    latestIndexFromPointerOrNull()?.let { return it }
    return scannedLatestIndex().also { rebuilt ->
        reconcileLatestIndexUnsafe(rebuilt)
    }
}

private suspend fun FileSystemAgentStorage.sessionEntry(
    entryIndex: Int,
    settingsIndex: Int,
    timestampIndex: Int,
): KodexSessionEntry =
    FileSystemSessionEntry(
        entryIndex = entryIndex,
        threadName = settingsIndex.takeIf { it >= 0 }?.let { index -> settings.getUnsafe(index).threadName },
        lastActivityAt = timestampIndex.takeIf { it >= 0 }?.let { index -> timestamp.getUnsafe(index) },
    )

/** Creates a filesystem session repository, initializing its root layout when needed. */
public suspend fun CoroutineScope.FileSystemKodexSessionRepository(
    root: Path,
    dependencies: KodexAgentDependencies,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    valueCacheSize: Int = 1_024,
): FileSystemKodexSessionRepository {
    fileSystem.createDirectories(root)
    val sessionsRoot = Path(root, SessionsDirectory)
    fileSystem.createDirectories(sessionsRoot)
    return FileSystemKodexSessionRepository(
        scope = supervisorChildScope(),
        root = root,
        fileSystem = fileSystem,
        valueCacheSize = valueCacheSize,
        dependencies = dependencies,
        initialEntries = sessionDirectories(sessionsRoot, fileSystem).map { (index) -> index },
    )
}

private const val SessionsDirectory: String = "sessions"
internal const val SubagentsDirectory: String = "subagents"
internal const val LockFile: String = "lock.json"
internal const val ArchiveMarkerFile: String = "archive.mark"
internal val SessionLeaseDuration: Duration = 30.seconds
