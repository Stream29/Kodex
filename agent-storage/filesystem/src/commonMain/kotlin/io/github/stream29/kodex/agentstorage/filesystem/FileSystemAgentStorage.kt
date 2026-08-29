package io.github.stream29.kodex.agentstorage.filesystem

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableWorkEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
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

    override val index: FileSystemIndexVersioned<CleanIndexEntry> =
        FileSystemIndexVersioned(
            Path(directory, IndexDirectory),
            CleanIndexEntry.serializer(),
            OpenAiJsonCodec,
            fileSystem,
        )
    override val work: FileSystemIndexVersioned<StableWorkEvent> =
        FileSystemIndexVersioned(
            Path(directory, WorkDirectory),
            StableWorkEvent.serializer(),
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

internal const val IndexDirectory: String = "index"
internal const val WorkDirectory: String = "work"
internal const val SettingsDirectory: String = "settings"
internal const val TimestampDirectory: String = "timestamp"
internal const val TokenCountDirectory: String = "token-count"
internal const val UnstableDirectory: String = "unstable"

internal val TimelineDirectories: List<String> = listOf(
    IndexDirectory,
    WorkDirectory,
    SettingsDirectory,
    TimestampDirectory,
    TokenCountDirectory,
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
        storage.index.reconcileLatestIndexUnsafe(-1)
        storage.work.reconcileLatestIndexUnsafe(-1)
        storage.settings.reconcileLatestIndexUnsafe(-1)
        storage.timestamp.reconcileLatestIndexUnsafe(-1)
        storage.tokenCount.reconcileLatestIndexUnsafe(-1)
        storage.unstable.reconcileLatestIndexUnsafe(-1)
    }
}

/**
 * Copies one initialized history prefix without decoding timeline payloads.
 */
public suspend fun FileSystemAgentStorage.forkRawTo(
    until: Int,
    target: FileSystemAgentStorage,
) {
    require(this !== target) { "Cannot fork storage into itself." }
    require(until > 0) { "Fork boundary must retain the initialized snapshot." }
    require(until <= latestIndex() + 1) {
        "Fork boundary $until exceeds the source history."
    }
    index.copyRangeRawTo(0, until, target.index)
    work.copyRangeRawTo(0, until, target.work)
    settings.copyRangeRawTo(0, until, target.settings)
    timestamp.copyRangeRawTo(0, until, target.timestamp)
    tokenCount.copyRangeRawTo(0, until, target.tokenCount)
    unstable.copyRangeRawTo(0, until, target.unstable)
}

internal suspend fun deleteRecursively(fileSystem: CoroutineFileSystem, path: Path) {
    val metadata = fileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        fileSystem.list(path).forEach { child -> deleteRecursively(fileSystem, child) }
    }
    fileSystem.delete(path, mustExist = false)
}
