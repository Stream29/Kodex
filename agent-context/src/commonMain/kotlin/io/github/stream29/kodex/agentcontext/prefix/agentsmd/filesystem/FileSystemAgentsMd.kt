package io.github.stream29.kodex.agentcontext.prefix.agentsmd.filesystem

import io.github.stream29.kodex.agentcontext.contract.AgentContextSourcePlan
import io.github.stream29.kodex.agentcontext.prefix.agentsmd.contract.AgentsMdInstruction
import io.github.stream29.kodex.agentcontext.prefix.agentsmd.contract.AgentsMdInstructions
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.readByteArray

internal sealed interface AgentsMdWarning {
    val source: Path

    data class ReadFailed(
        override val source: Path,
        val message: String,
    ) : AgentsMdWarning

    data class InvalidUtf8(
        override val source: Path,
    ) : AgentsMdWarning

    data class Truncated(
        override val source: Path,
        val originalByteCount: Long,
        val acceptedByteCount: Int,
    ) : AgentsMdWarning
}

internal data class AgentsMdSnapshot(
    val instructions: AgentsMdInstructions,
    val warnings: List<AgentsMdWarning>,
)

internal suspend fun loadAgentsMd(
    plan: AgentContextSourcePlan,
    projectDocMaxBytes: Int = 32 * 1024,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): AgentsMdSnapshot {
    require(projectDocMaxBytes >= 0) { "Project document byte budget must be non-negative." }
    val discoveredSources = mutableSetOf<Path>()
    val global = loadInstructions(
        roots = plan.globalRoots,
        projectDocMaxBytes = null,
        discoveredSources = discoveredSources,
        fileSystem = fileSystem,
    )
    val project = loadInstructions(
        roots = plan.projectRoots,
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
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
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
private const val ReadSegmentByteCount: Long = 64 * 1024L
