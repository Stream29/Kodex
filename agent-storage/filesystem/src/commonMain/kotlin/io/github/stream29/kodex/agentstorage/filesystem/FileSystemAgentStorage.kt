package io.github.stream29.kodex.agentstorage.filesystem

import io.github.stream29.kodex.agentstorage.cleanmodels.CleanCompactionCheckpoint
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.indexes
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.serializer
import kotlin.time.Instant

/** Unowned direct view of the six timelines stored under one thread directory. */
public class FileSystemAgentStorage internal constructor(
    public val directory: Path,
    fileSystem: CoroutineFileSystem,
    @Suppress("UNUSED_PARAMETER") construction: Unit,
) : MutableKodexAgentStorage {
    override val id: String = "filesystem:$directory"

    override val compaction: FileSystemIndexVersioned<CleanCompactionCheckpoint> =
        FileSystemIndexVersioned(
            Path(directory, CompactionDirectory),
            CleanCompactionCheckpoint.serializer(),
            OpenAiJsonCodec,
            fileSystem,
        )
    override val settings: FileSystemIndexVersioned<KodexAgentSettings> =
        FileSystemIndexVersioned(
            Path(directory, SettingsDirectory),
            KodexAgentSettings.serializer(),
            OpenAiJsonCodec,
            fileSystem,
        )
    override val timestamp: FileSystemIndexVersioned<Instant> =
        FileSystemIndexVersioned(
            Path(directory, TimestampDirectory),
            serializer<Instant>(),
            OpenAiJsonCodec,
            fileSystem,
        )
    override val tokenCount: FileSystemIndexVersioned<Long> =
        FileSystemIndexVersioned(
            Path(directory, TokenCountDirectory),
            Long.serializer(),
            OpenAiJsonCodec,
            fileSystem,
        )
    override val stable: FileSystemIndexVersioned<StableCleanEvent> =
        FileSystemIndexVersioned(
            Path(directory, StableDirectory),
            StableCleanEvent.serializer(),
            OpenAiJsonCodec,
            fileSystem,
        )
    override val unstable: FileSystemIndexVersioned<List<UnstableCleanEvent>> =
        FileSystemIndexVersioned(
            Path(directory, UnstableDirectory),
            ListSerializer(UnstableCleanEvent.serializer()),
            OpenAiJsonCodec,
            fileSystem,
        )

    public companion object
}

/** Opens an existing thread directory without acquiring ownership. */
public suspend fun FileSystemAgentStorage(
    directory: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): FileSystemAgentStorage {
    val resolved = fileSystem.resolve(directory)
    return FileSystemAgentStorage(
        directory = resolved,
        fileSystem = fileSystem,
        construction = Unit,
    )
}

internal const val CompactionDirectory: String = "compaction"
internal const val SettingsDirectory: String = "settings"
internal const val TimestampDirectory: String = "timestamp"
internal const val TokenCountDirectory: String = "token-count"
internal const val StableDirectory: String = "stable"
internal const val UnstableDirectory: String = "unstable"

internal val TimelineDirectories: List<String> = listOf(
    CompactionDirectory,
    SettingsDirectory,
    TimestampDirectory,
    TokenCountDirectory,
    StableDirectory,
    UnstableDirectory,
)

/** Directory names that make up one filesystem AgentStorage. */
public val FileSystemAgentStorageTimelineDirectories: List<String> = TimelineDirectories

/**
 * Creates the uninitialized storage directory backing a freshly spawned AgentSession.
 *
 * Set [mustCreateDirectory] to `false` only when the caller has already reserved [directory].
 */
public suspend fun FileSystemAgentStorage.Companion.ofEmpty(
    directory: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    mustCreateDirectory: Boolean = true,
): FileSystemAgentStorage {
    fileSystem.createDirectories(directory, mustCreate = mustCreateDirectory)
    TimelineDirectories.forEach { name ->
        fileSystem.createDirectories(Path(directory, name), mustCreate = true)
    }
    return FileSystemAgentStorage(directory, fileSystem).also { storage ->
        storage.compaction.reconcileLatestIndexUnsafe(-1)
        storage.settings.reconcileLatestIndexUnsafe(-1)
        storage.timestamp.reconcileLatestIndexUnsafe(-1)
        storage.tokenCount.reconcileLatestIndexUnsafe(-1)
        storage.stable.reconcileLatestIndexUnsafe(-1)
        storage.unstable.reconcileLatestIndexUnsafe(-1)
    }
}

/**
 * Fast same-format fork: raw-copy unchanged timeline files and decode only
 * compaction checkpoints, whose history base must be rebased.
 */
public suspend fun FileSystemAgentStorage.forkRangeRawTo(
    from: Int,
    until: Int,
    target: FileSystemAgentStorage,
) {
    require(this !== target) { "Cannot fork storage into itself." }
    require(from >= 0 && until > from) { "Invalid fork range [$from, $until)." }
    require(until <= latestIndex() + 1) {
        "Fork range [$from, $until) exceeds the source history."
    }
    val checkpointIndex = compaction.floorToIndex(from)
    if (checkpointIndex != null) {
        val checkpoint = compaction.getUnsafe(checkpointIndex)
        target.compaction.setUnsafe(
            0,
            checkpoint.copy(historyBaseIndex = checkpoint.historyBaseIndex - from),
        )
    }
    compaction.indexes(from + 1).collect { index ->
        if (index < until) {
            val checkpoint = compaction.getUnsafe(index)
            target.compaction.setUnsafe(
                index - from,
                checkpoint.copy(historyBaseIndex = checkpoint.historyBaseIndex - from),
            )
        }
    }
    settings.copyRangeRawTo(from, until, target.settings, snapshotAtStart = true)
    timestamp.copyRangeRawTo(from, until, target.timestamp, snapshotAtStart = true)
    tokenCount.copyRangeRawTo(from, until, target.tokenCount, snapshotAtStart = true)
    stable.copyRangeRawTo(from, until, target.stable)
    unstable.copyRangeRawTo(from, until, target.unstable, snapshotAtStart = true)
}

internal suspend fun deleteRecursively(fileSystem: CoroutineFileSystem, path: Path) {
    val metadata = fileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        fileSystem.list(path).forEach { child -> deleteRecursively(fileSystem, child) }
    }
    fileSystem.delete(path, mustExist = false)
}
