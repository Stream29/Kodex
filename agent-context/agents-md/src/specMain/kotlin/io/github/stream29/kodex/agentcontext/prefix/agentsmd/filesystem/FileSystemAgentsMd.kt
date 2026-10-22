package io.github.stream29.kodex.agentcontext.prefix.agentsmd.filesystem

import io.github.stream29.kodex.agentcontext.contract.AgentContextSourcePlan
import io.github.stream29.kodex.agentcontext.prefix.agentsmd.contract.AgentsMdSnapshot
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.io.files.Path

/**
 * Loads a fresh AGENTS.md discovery snapshot from the current filesystem state.
 * Ordinary source-read exceptions become warnings; discovery failures still propagate.
 *
 * @throws IllegalArgumentException if [projectDocMaxBytes] is negative.
 * @throws CancellationException if a filesystem operation or the caller is cancelled.
 * @throws Error if the filesystem raises a non-recoverable error.
 */
public expect suspend fun loadAgentsMd(
    agentsHome: Path,
    kodexHome: Path,
    cwd: Path,
    projectRootMarkers: List<String> = listOf(".git"),
    projectDocMaxBytes: Int = 32 * 1024,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): AgentsMdSnapshot

/**
 * Loads a fresh snapshot from one shared, already deduplicated source plan.
 * Ordinary source-read exceptions become warnings; discovery failures still propagate.
 *
 * @throws IllegalArgumentException if [projectDocMaxBytes] is negative.
 * @throws CancellationException if a filesystem operation or the caller is cancelled.
 * @throws Error if the filesystem raises a non-recoverable error.
 */
public expect suspend fun loadAgentsMd(
    plan: AgentContextSourcePlan,
    projectDocMaxBytes: Int = 32 * 1024,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): AgentsMdSnapshot
