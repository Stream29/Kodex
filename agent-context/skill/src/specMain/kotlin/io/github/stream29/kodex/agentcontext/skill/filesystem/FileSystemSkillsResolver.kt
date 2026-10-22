@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentcontext.skill.filesystem

import io.github.stream29.kodex.agentcontext.contract.AgentContextSettings
import io.github.stream29.kodex.agentcontext.contract.AgentContextSourcePlan
import io.github.stream29.kodex.agentcontext.skill.contract.ResolvedSkills
import io.github.stream29.kodex.agentcontext.skill.contract.SkillsResolver
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path

/**
 * Filesystem resolver for configured global skills and the exact project
 * endpoints containing the requested cwd.
 *
 * The metadata cache lives for the lifetime of this resolver. Each [resolve]
 * reads the current [contextSettings], so changing either global home changes
 * the global skill roots without replacing the cache-owning resolver.
 */
public expect class FileSystemSkillsResolver(
    contextSettings: StateFlow<AgentContextSettings>,
    projectRootMarkers: List<String> = listOf(".git"),
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    metadataCacheCapacity: Int = 512,
) : SkillsResolver {
    override suspend fun resolve(cwd: Path): ResolvedSkills

    /**
     * Resolves [cwd] against an already captured [context].
     *
     * This overload keeps a larger context-prefix capture internally
     * consistent while the public resolver continues observing its StateFlow.
     */
    public suspend fun resolve(cwd: Path, context: AgentContextSettings): ResolvedSkills

    /** Resolves skills from one shared, already deduplicated source plan. */
    public suspend fun resolve(plan: AgentContextSourcePlan): ResolvedSkills
}
