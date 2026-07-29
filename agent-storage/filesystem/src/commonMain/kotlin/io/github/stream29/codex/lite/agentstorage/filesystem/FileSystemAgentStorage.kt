package io.github.stream29.codex.lite.agentstorage.filesystem

import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.codex.lite.agentstorage.contract.MutableCodexAgentStorage
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.CompactionCheckpoint
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.serializer
import kotlin.time.Instant

/** Unowned direct view of the seven timelines stored under one thread directory. */
public class FileSystemAgentStorage internal constructor(
    public val directory: Path,
    fileSystem: CoroutineFileSystem,
    @Suppress("UNUSED_PARAMETER") construction: Unit,
) : MutableCodexAgentStorage {
    override val id: String = "filesystem:$directory"

    override val history: FileSystemIndexVersioned<ResponseItem.HistoryItem> =
        FileSystemIndexVersioned(
            Path(directory, HistoryDirectory),
            ResponseItem.HistoryItem.serializer(),
            OpenAiJsonCodec,
            fileSystem,
        )
    override val compaction: FileSystemIndexVersioned<CompactionCheckpoint> =
        FileSystemIndexVersioned(
            Path(directory, CompactionDirectory),
            CompactionCheckpoint.serializer(),
            OpenAiJsonCodec,
            fileSystem,
        )
    override val settings: FileSystemIndexVersioned<CodexAgentSettings> =
        FileSystemIndexVersioned(
            Path(directory, SettingsDirectory),
            CodexAgentSettings.serializer(),
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
    override val unstable: FileSystemIndexVersioned<List<PendingToolEvent>> =
        FileSystemIndexVersioned(
            Path(directory, UnstableDirectory),
            ListSerializer(PendingToolEvent.serializer()),
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

internal const val HistoryDirectory: String = "history"
internal const val CompactionDirectory: String = "compaction"
internal const val SettingsDirectory: String = "settings"
internal const val TimestampDirectory: String = "timestamp"
internal const val TokenCountDirectory: String = "token-count"
internal const val StableDirectory: String = "stable"
internal const val UnstableDirectory: String = "unstable"

internal val TimelineDirectories: List<String> = listOf(
    HistoryDirectory,
    CompactionDirectory,
    SettingsDirectory,
    TimestampDirectory,
    TokenCountDirectory,
    StableDirectory,
    UnstableDirectory,
)

/** Directory names that make up one filesystem AgentStorage. */
public val FileSystemAgentStorageTimelineDirectories: List<String> = TimelineDirectories

/** Creates the uninitialized storage directory backing a freshly spawned AgentSession. */
public suspend fun FileSystemAgentStorage.Companion.ofEmpty(
    directory: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): FileSystemAgentStorage {
    fileSystem.createDirectories(directory, mustCreate = true)
    TimelineDirectories.forEach { name ->
        fileSystem.createDirectories(Path(directory, name), mustCreate = true)
    }
    return FileSystemAgentStorage(directory, fileSystem)
}

internal suspend fun deleteRecursively(fileSystem: CoroutineFileSystem, path: Path) {
    val metadata = fileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        fileSystem.list(path).forEach { child -> deleteRecursively(fileSystem, child) }
    }
    fileSystem.delete(path, mustExist = false)
}
