package io.github.stream29.kodex.agentcontext.prefix.impl

import io.github.stream29.kodex.agentcontext.prefix.agentsmd.filesystem.loadAgentsMd
import io.github.stream29.kodex.agentcontext.contract.AgentContextCustomSource
import io.github.stream29.kodex.agentcontext.contract.AgentContextSourcePlan
import io.github.stream29.kodex.agentcontext.contract.AgentContextSettings
import io.github.stream29.kodex.agentcontext.prefix.contract.AgentContextPrefix
import io.github.stream29.kodex.agentcontext.skill.filesystem.FileSystemSkillsResolver
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.osenvironment.requireUserHomeDirectory
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path

/**
 * Resolves application-wide and Agent-specific data into one request prefix.
 *
 * AgentState owns this helper rather than accepting an executable context
 * provider. Each [resolve] call captures one complete [contextSettings]
 * snapshot together with the supplied Agent settings.
 */
public class AgentContextPrefixResolver(
    private val contextSettings: StateFlow<AgentContextSettings>,
) {
    private val skillsResolver = FileSystemSkillsResolver(
        contextSettings = contextSettings,
    )

    /** Captures global state and resolves Agent-specific context for [settings]. */
    public suspend fun resolve(settings: KodexAgentSettings): AgentContextPrefix {
        val context = contextSettings.value
        val cwd = settings.cwd
        val plan = resolveSourcePlan(context, cwd)
        val agentsMd = loadAgentsMd(plan)
        val skills = skillsResolver.resolve(plan)
        return AgentContextPrefix(
            cwd = cwd,
            shell = context.shell,
            agentMd = agentsMd.instructions,
            availableSkills = skills.skills,
        )
    }
}

private suspend fun resolveSourcePlan(
    context: AgentContextSettings,
    cwd: Path,
): AgentContextSourcePlan {
    val fileSystem = SystemCoroutineFileSystem
    val resolvedCwd = fileSystem.resolve(cwd)
    val global = buildList {
        if (context.sources.agentsHomeEnabled) add(context.agentsHome)
        if (context.sources.kodexHomeEnabled) add(context.kodexHome)
        if (context.sources.codexHomeEnabled) add(context.codexHome)
        context.sources.customSources
            .filter(AgentContextCustomSource::enabled)
            .mapNotNull { source -> source.path.toContextPath() }
            .forEach { path -> add(path) }
    }.map { path -> fileSystem.resolveAllowingMissing(path) }
    val project = buildList {
        if (context.sources.gitRootEnabled) {
            nearestProjectRoot(resolvedCwd, fileSystem)?.let(::add)
        }
        if (context.sources.workingDirectoryEnabled) add(resolvedCwd)
    }
    val selected = linkedMapOf<Path, Boolean>()
    global.forEach { path -> selected.putIfAbsent(path, false) }
    project.forEach { path ->
        selected.remove(path)
        selected[path] = true
    }
    return AgentContextSourcePlan(
        globalRoots = selected.filterValues { isProject -> !isProject }.keys.toList(),
        projectRoots = selected.filterValues { isProject -> isProject }.keys.toList(),
    )
}

private suspend fun nearestProjectRoot(
    cwd: Path,
    fileSystem: io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem,
): Path? {
    var cursor: Path? = cwd
    while (cursor != null) {
        if (fileSystem.metadataOrNull(Path(cursor, ".git")) != null) return cursor
        cursor = cursor.parent
    }
    return null
}

private fun String.toContextPath(): Path? {
    if (isBlank() || startsWith("$")) return null
    val home = Path(requireUserHomeDirectory())
    return when {
        this == "~" -> home
        startsWith("~/") -> Path(home, substring(2))
        else -> Path(this).takeIf(Path::isAbsolute)
    }
}

private suspend fun io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
    .resolveAllowingMissing(path: Path): Path {
    if (metadataOrNull(path) != null) return resolve(path)
    val parent = path.parent
    val resolvedParent = if (parent == null) resolve(Path(".")) else resolveAllowingMissing(parent)
    return Path(resolvedParent, path.name)
}

private fun <K, V> MutableMap<K, V>.putIfAbsent(key: K, value: V) {
    if (key !in this) this[key] = value
}
