package io.github.stream29.kodex.agentcontext.prefix.agentsmd.filesystem

import io.github.stream29.kodex.agentcontext.prefix.agentsmd.contract.AgentsMdInstruction
import io.github.stream29.kodex.agentcontext.prefix.agentsmd.contract.AgentsMdInstructions
import io.github.stream29.kodex.agentcontext.prefix.agentsmd.contract.AgentsMdSnapshot
import io.github.stream29.kodex.agentcontext.prefix.agentsmd.contract.AgentsMdWarning
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.readByteArray

/** Loads a fresh AGENTS.md discovery snapshot from the current filesystem state. */
public suspend fun loadAgentsMd(
    agentsHome: Path,
    kodexHome: Path,
    cwd: Path,
    projectRootMarkers: List<String> = listOf(".git"),
    projectDocMaxBytes: Int = DefaultProjectDocMaxBytes,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): AgentsMdSnapshot {
    require(projectDocMaxBytes >= 0) { "Project document byte budget must be non-negative." }
    val roots = discoveryRoots(
        agentsHome = agentsHome,
        kodexHome = kodexHome,
        cwd = cwd,
        projectRootMarkers = projectRootMarkers,
        fileSystem = fileSystem,
    )
    val discoveredSources = mutableSetOf<Path>()
    val global = loadInstructions(
        roots = roots.global,
        projectDocMaxBytes = null,
        discoveredSources = discoveredSources,
        fileSystem = fileSystem,
    )
    val project = loadInstructions(
        roots = roots.project,
        projectDocMaxBytes = projectDocMaxBytes,
        discoveredSources = discoveredSources,
        fileSystem = fileSystem,
    )
    return AgentsMdSnapshot(
        instructions = AgentsMdInstructions(
            globalInstructions = global.value,
            projectInstructions = project.value,
        ),
        warnings = global.warnings + project.warnings,
    )
}

private suspend fun loadInstructions(
    roots: List<Path>,
    projectDocMaxBytes: Int?,
    discoveredSources: MutableSet<Path>,
    fileSystem: CoroutineFileSystem,
): Loaded<List<AgentsMdInstruction>> {
    var remaining = projectDocMaxBytes
    var instructions: List<AgentsMdInstruction> = emptyList()
    var warnings: List<AgentsMdWarning> = emptyList()
    for (root in roots) {
        if (remaining == 0) break
        val source = Path(root, AgentsMdFileName)
        if (fileSystem.metadataOrNull(source)?.isRegularFile != true) continue
        val resolved = fileSystem.resolve(source)
        if (!discoveredSources.add(resolved)) continue
        val metadata = fileSystem.metadataOrNull(resolved) ?: continue
        val bytes = when (
            val result = readBytes(
                fileSystem = fileSystem,
                source = resolved,
                maxByteCount = remaining?.toLong() ?: Long.MAX_VALUE,
            )
        ) {
            is ReadBytesResult.Success -> result.bytes
            is ReadBytesResult.Failure -> {
                warnings = warnings + result.warning
                continue
            }
        }
        if (remaining != null && metadata.size > bytes.size) {
            warnings = warnings + AgentsMdWarning.Truncated(
                source = resolved,
                originalByteCount = metadata.size,
                acceptedByteCount = bytes.size,
            )
        }
        val decoded = decode(bytes, resolved)
        warnings = warnings + decoded.warnings
        if (decoded.value.isBlank()) continue
        instructions = instructions + AgentsMdInstruction(
            source = resolved,
            text = decoded.value,
        )
        if (remaining != null) remaining -= bytes.size
    }
    return Loaded(instructions, warnings)
}

private suspend fun readBytes(
    fileSystem: CoroutineFileSystem,
    source: Path,
    maxByteCount: Long = Long.MAX_VALUE,
): ReadBytesResult = try {
    val bytes = if (maxByteCount == Long.MAX_VALUE) {
        fileSystem.readBytes(source)
    } else {
        fileSystem.useSource(source) { input ->
            val buffer = Buffer()
            var remaining = maxByteCount
            while (remaining > 0L) {
                val read = input.readAtMostTo(buffer, minOf(remaining, ReadSegmentByteCount))
                if (read == -1L) break
                remaining -= read
            }
            buffer.readByteArray()
        }
    }
    ReadBytesResult.Success(bytes)
} catch (failure: Throwable) {
    currentCoroutineContext().ensureActive()
    ReadBytesResult.Failure(AgentsMdWarning.ReadFailed(source, failure.toString()))
}

private fun decode(
    bytes: ByteArray,
    source: Path,
): Loaded<String> {
    val text = bytes.decodeToString()
    val warnings = if (text.encodeToByteArray().contentEquals(bytes)) {
        emptyList()
    } else {
        listOf(AgentsMdWarning.InvalidUtf8(source))
    }
    return Loaded(text, warnings)
}

private suspend fun discoveryRoots(
    agentsHome: Path,
    kodexHome: Path,
    cwd: Path,
    projectRootMarkers: List<String>,
    fileSystem: CoroutineFileSystem,
): AgentsMdRoots {
    val resolvedCwd = fileSystem.resolve(cwd)
    val projectRoot = nearestProjectRoot(resolvedCwd, projectRootMarkers, fileSystem)
    val ordered = buildList {
        add(AgentsMdRoot(fileSystem.resolveAllowingMissing(agentsHome), AgentsMdRootScope.Global))
        add(AgentsMdRoot(fileSystem.resolveAllowingMissing(kodexHome), AgentsMdRootScope.Global))
        projectRoot?.let { root -> add(AgentsMdRoot(root, AgentsMdRootScope.Project)) }
        add(AgentsMdRoot(resolvedCwd, AgentsMdRootScope.Project))
    }
    val unique = linkedMapOf<Path, AgentsMdRoot>()
    ordered.forEach { root ->
        if (root.path !in unique) unique[root.path] = root
    }
    return AgentsMdRoots(
        global = unique.values
            .filter { root -> root.scope == AgentsMdRootScope.Global }
            .map(AgentsMdRoot::path),
        project = unique.values
            .filter { root -> root.scope == AgentsMdRootScope.Project }
            .map(AgentsMdRoot::path),
    )
}

private suspend fun nearestProjectRoot(
    cwd: Path,
    projectRootMarkers: List<String>,
    fileSystem: CoroutineFileSystem,
): Path? {
    if (projectRootMarkers.isEmpty()) return null
    var cursor: Path? = cwd
    while (cursor != null) {
        val current = cursor
        if (projectRootMarkers.any { marker -> fileSystem.metadataOrNull(Path(current, marker)) != null }) {
            return current
        }
        cursor = current.parent
    }
    return null
}

private suspend fun CoroutineFileSystem.resolveAllowingMissing(path: Path): Path {
    if (metadataOrNull(path) != null) return resolve(path)
    val parent = path.parent
    val resolvedParent = if (parent == null) resolve(Path(".")) else resolveAllowingMissing(parent)
    return Path(resolvedParent, path.name)
}

private data class Loaded<out T>(
    val value: T,
    val warnings: List<AgentsMdWarning> = emptyList(),
)

private sealed interface ReadBytesResult {
    class Success(
        val bytes: ByteArray,
    ) : ReadBytesResult

    data class Failure(
        val warning: AgentsMdWarning.ReadFailed,
    ) : ReadBytesResult
}

private data class AgentsMdRoots(
    val global: List<Path>,
    val project: List<Path>,
)

private data class AgentsMdRoot(
    val path: Path,
    val scope: AgentsMdRootScope,
)

private enum class AgentsMdRootScope {
    Global,
    Project,
}

private const val AgentsMdFileName: String = "AGENTS.md"
private const val DefaultProjectDocMaxBytes: Int = 32 * 1024
private const val ReadSegmentByteCount: Long = 64 * 1024L
