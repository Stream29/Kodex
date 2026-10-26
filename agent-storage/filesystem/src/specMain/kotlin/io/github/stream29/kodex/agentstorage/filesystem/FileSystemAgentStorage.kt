@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentstorage.filesystem

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWorkEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.TokenCountSnapshot
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlin.time.Instant

/** Unowned direct view of the six timelines stored under one thread directory. */
public expect class FileSystemAgentStorage : MutableKodexAgentStorage {
    public val directory: Path
    override val uri: String
    override val index: FileSystemIndexVersioned<CleanIndexEntry>
    override val work: FileSystemIndexVersioned<StableWorkEvent>
    override val settings: FileSystemIndexVersioned<KodexAgentSettings>
    override val timestamp: FileSystemIndexVersioned<Instant>
    override val tokenCount: FileSystemIndexVersioned<TokenCountSnapshot>
    override val unstable: FileSystemIndexVersioned<List<UnstableCleanEvent>>
    public companion object
}

/** Opens an existing thread directory without acquiring ownership. */
public expect suspend fun FileSystemAgentStorage(
    directory: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): FileSystemAgentStorage

/** Directory names that make up one filesystem AgentStorage. */
public expect val FileSystemAgentStorageTimelineDirectories: List<String>

/**
 * Creates the uninitialized storage directory backing a freshly spawned AgentSession.
 *
 * Set [mustCreateDirectory] to `false` only when the caller has already reserved [directory].
 */
public expect suspend fun FileSystemAgentStorage.Companion.ofEmpty(
    directory: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    mustCreateDirectory: Boolean = true,
): FileSystemAgentStorage

/**
 * Copies one initialized history prefix without decoding timeline payloads.
 */
public expect suspend fun FileSystemAgentStorage.forkRawTo(
    until: Int,
    target: FileSystemAgentStorage,
)
