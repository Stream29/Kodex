package io.github.stream29.kodex.agentcontext.prefix.agentsmd.filesystem

import io.github.stream29.kodex.agentcontext.prefix.agentsmd.contract.AgentsMdInstruction
import io.github.stream29.kodex.agentcontext.prefix.agentsmd.contract.AgentsMdInstructions
import io.github.stream29.kodex.agentcontext.prefix.agentsmd.contract.AgentsMdSnapshot
import io.github.stream29.kodex.agentcontext.prefix.agentsmd.contract.AgentsMdWarning
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.use
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.readByteArray

/** Loads a fresh AGENTS.md discovery snapshot from the current filesystem state. */
public suspend fun loadAgentsMd(
    agentsHome: Path,
    cwd: Path,
    projectRootMarkers: List<String> = listOf(".git"),
    projectDocFallbackFilenames: List<String> = emptyList(),
    projectDocMaxBytes: Int = DefaultProjectDocMaxBytes,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): AgentsMdSnapshot {
    require(projectDocMaxBytes >= 0) { "Project document byte budget must be non-negative." }
    val user = loadUserInstructions(agentsHome, fileSystem)
    val project = loadProjectInstructions(
        cwd = cwd,
        projectRootMarkers = projectRootMarkers,
        projectDocFallbackFilenames = projectDocFallbackFilenames,
        projectDocMaxBytes = projectDocMaxBytes,
        fileSystem = fileSystem,
    )
    return AgentsMdSnapshot(
        instructions = AgentsMdInstructions(
            userInstruction = user.value,
            projectInstructions = project.value,
        ),
        warnings = user.warnings + project.warnings,
    )
}

/**
 * @return Loaded user-level instructions. The wrapped value is `null` when
 * no readable, nonblank user Agent instruction file exists.
 */
private suspend fun loadUserInstructions(
    agentsHome: Path,
    fileSystem: CoroutineFileSystem,
): Loaded<AgentsMdInstruction?> {
    var warnings: List<AgentsMdWarning> = emptyList()
    for (name in listOf(AgentsOverrideFileName, AgentsMdFileName)) {
        val source = Path(agentsHome, name)
        if (fileSystem.metadataOrNull(source)?.isRegularFile != true) continue
        val resolved = fileSystem.resolve(source)
        val bytes = when (val result = readBytes(fileSystem, resolved)) {
            is ReadBytesResult.Success -> result.bytes
            is ReadBytesResult.Failure -> {
                warnings = warnings + result.warning
                continue
            }
        }
        val decoded = decode(bytes, resolved)
        warnings = warnings + decoded.warnings
        val text = decoded.value.trim()
        if (text.isNotEmpty()) {
            return Loaded(
                value = AgentsMdInstruction(resolved, text),
                warnings = warnings,
            )
        }
    }
    return Loaded(null, warnings)
}

private suspend fun loadProjectInstructions(
    cwd: Path,
    projectRootMarkers: List<String>,
    projectDocFallbackFilenames: List<String>,
    projectDocMaxBytes: Int,
    fileSystem: CoroutineFileSystem,
): Loaded<List<AgentsMdInstruction>> {
    if (projectDocMaxBytes == 0) return Loaded(emptyList())
    var remaining = projectDocMaxBytes
    var instructions: List<AgentsMdInstruction> = emptyList()
    var warnings: List<AgentsMdWarning> = emptyList()
    for (directory in projectDirectories(cwd, projectRootMarkers, fileSystem)) {
        if (remaining == 0) break
        val source = candidateNames(projectDocFallbackFilenames)
            .asSequence()
            .map { name -> Path(directory, name) }
            .firstOrNull { path -> fileSystem.metadataOrNull(path)?.isRegularFile == true }
            ?: continue
        val resolved = fileSystem.resolve(source)
        val metadata = fileSystem.metadataOrNull(resolved) ?: continue
        val bytes = when (
            val result = readBytes(
                fileSystem = fileSystem,
                source = resolved,
                maxByteCount = remaining.toLong(),
            )
        ) {
            is ReadBytesResult.Success -> result.bytes
            is ReadBytesResult.Failure -> {
                warnings = warnings + result.warning
                continue
            }
        }
        if (metadata.size > bytes.size) {
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
        remaining -= bytes.size
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
        fileSystem.source(source).use { input ->
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

private suspend fun projectDirectories(
    workingDirectory: Path,
    projectRootMarkers: List<String>,
    fileSystem: CoroutineFileSystem,
): List<Path> {
    val cwd = fileSystem.resolve(workingDirectory)
    if (projectRootMarkers.isEmpty()) return listOf(cwd)
    var cursor: Path? = cwd
    var root: Path? = null
    while (cursor != null) {
        val current = cursor
        if (projectRootMarkers.any { marker -> fileSystem.metadataOrNull(Path(current, marker)) != null }) {
            root = current
            break
        }
        cursor = current.parent
    }
    val projectRoot = root ?: cwd
    return buildList {
        var current: Path? = cwd
        while (current != null) {
            add(current)
            if (current == projectRoot) break
            current = current.parent
        }
    }.asReversed()
}

private fun candidateNames(projectDocFallbackFilenames: List<String>): List<String> = buildList {
    add(AgentsOverrideFileName)
    add(AgentsMdFileName)
    projectDocFallbackFilenames.forEach { name ->
        if (name.isNotEmpty() && name !in this) add(name)
    }
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

private const val AgentsMdFileName: String = "AGENTS.md"
private const val AgentsOverrideFileName: String = "AGENTS.override.md"
private const val DefaultProjectDocMaxBytes: Int = 32 * 1024
private const val ReadSegmentByteCount: Long = 64 * 1024L
