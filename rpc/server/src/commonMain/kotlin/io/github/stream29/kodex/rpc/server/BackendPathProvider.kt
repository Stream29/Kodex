package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.agentcontext.contract.ContextPathComparison
import io.github.stream29.kodex.agentcontext.contract.ContextPathEvaluation
import io.github.stream29.kodex.agentcontext.contract.ContextPathEvaluationFailure
import io.github.stream29.kodex.rpc.models.BackendChildDirectory
import io.github.stream29.kodex.rpc.models.ContextPathQuery
import io.github.stream29.kodex.rpc.models.DirectoryListResult
import io.github.stream29.kodex.rpc.models.DirectoryQuery
import io.github.stream29.kodex.rpc.models.DirectoryQueryFailure
import io.github.stream29.kodex.rpc.models.DirectoryValidationResult
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.files.Path
import kotlinx.io.files.SystemPathSeparator

/**
 * Borrowed backend environment, without a picker store, launch cwd fallback or resource owner.
 * [agentsHome], [kodexHome] and [codexHome] must be the roots supplied to BackendGlobalState.
 * [userHome] is the OS user's Home, not the possibly overridden Kodex storage Home.
 */
internal class BackendPathProvider(
    private val fileSystem: CoroutineFileSystem,
    private val userHome: Path?,
    private val agentsHome: Path,
    private val kodexHome: Path,
    private val codexHome: Path,
    private val observationBudgetBytes: Long = DefaultObservationBudgetBytes,
) {
    init {
        require(observationBudgetBytes > 0)
    }

    suspend fun validateDirectory(query: DirectoryQuery): DirectoryValidationResult {
        var parent: String? = null
        return try {
            currentCoroutineContext().ensureActive()
            val budget = PathObservationBudget(observationBudgetBytes)
            budget.string(query.launchBaseDirectory)
            budget.string(query.path)
            val base = launchBaseOrNull(query.launchBaseDirectory)
                ?: throw PathQueryRejected(DirectoryQueryFailure.InvalidLaunchBase)
            val expanded = expandDirectory(query.path, base)
            parent = expanded.parent?.toString()
            // Only absolute arguments reach resolve: the daemon's cwd is never a base.
            val resolved = fileSystem.resolve(expanded)
            currentCoroutineContext().ensureActive()
            if (!resolved.isAbsolute) throw PathQueryRejected(DirectoryQueryFailure.FileSystem)
            parent = resolved.parent?.toString()
            budget.string(resolved.toString())
            parent?.let(budget::string)
            val metadata = fileSystem.metadataOrNull(resolved)
            currentCoroutineContext().ensureActive()
            if (metadata?.isDirectory == true) DirectoryValidationResult.Directory(resolved.toString())
            else DirectoryValidationResult.Failure(DirectoryQueryFailure.NotDirectory, parent)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: PathQueryRejected) {
            DirectoryValidationResult.Failure(rejected.reason)
        } catch (_: PathObservationLimitExceeded) {
            DirectoryValidationResult.Failure(DirectoryQueryFailure.LimitExceeded)
        } catch (_: Exception) {
            DirectoryValidationResult.Failure(DirectoryQueryFailure.FileSystem, parent)
        }
    }

    suspend fun listDirectories(query: DirectoryQuery): DirectoryListResult {
        val directory = when (val result = validateDirectory(query)) {
            is DirectoryValidationResult.Directory -> Path(result.path)
            is DirectoryValidationResult.Failure ->
                return DirectoryListResult.Failure(result.reason, result.parentDirectory)
        }
        val parent = directory.parent?.toString()
        return try {
            val budget = PathObservationBudget(observationBudgetBytes)
            budget.string(directory.toString())
            parent?.let(budget::string)
            val children = mutableListOf<BackendChildDirectory>()
            currentCoroutineContext().ensureActive()
            val entries = fileSystem.list(directory)
            currentCoroutineContext().ensureActive()
            for (child in entries) {
                currentCoroutineContext().ensureActive()
                // Charge work even for files/dangling links. Never return a truncated success.
                budget.entry()
                val metadata = fileSystem.metadataOrNull(child)
                currentCoroutineContext().ensureActive()
                if (metadata?.isDirectory != true) continue
                val path = child.toString()
                val name = child.name
                budget.string(path)
                budget.string(name)
                children += BackendChildDirectory(path, name)
            }
            val ordered = children.sortedWith(
                compareBy<BackendChildDirectory> { it.name.lowercase() }.thenBy { it.path },
            )
            currentCoroutineContext().ensureActive()
            DirectoryListResult.Directories(directory.toString(), parent, ordered)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: PathObservationLimitExceeded) {
            DirectoryListResult.Failure(DirectoryQueryFailure.LimitExceeded)
        } catch (_: Exception) {
            DirectoryListResult.Failure(DirectoryQueryFailure.FileSystem, parent)
        }
    }

    /** Lexical only: neither resolve nor metadata/list/source is used for Context evaluation. */
    suspend fun evaluateContextPaths(query: ContextPathQuery): ContextPathEvaluation {
        return try {
            currentCoroutineContext().ensureActive()
            val budget = PathObservationBudget(observationBudgetBytes)
            budget.string(query.launchBaseDirectory)
            if (launchBaseOrNull(query.launchBaseDirectory) == null) {
                return ContextPathEvaluation.Failure(ContextPathEvaluationFailure.InvalidLaunchBase)
            }
            val home = userHome?.takeIf { absolutePathOrNull(it.toString()) != null }
                ?: return ContextPathEvaluation.Failure(ContextPathEvaluationFailure.EnvironmentUnavailable)
            val roots = listOf(agentsHome, kodexHome, codexHome)
            if (roots.any { absolutePathOrNull(it.toString()) == null }) {
                return ContextPathEvaluation.Failure(ContextPathEvaluationFailure.EnvironmentUnavailable)
            }
            val builtIns = roots.map { it.toString() }.toSet()
            fun compare(spelling: String): ContextPathComparison {
                budget.entry()
                budget.string(spelling)
                val key = contextLexicalKey(spelling, home)
                if (key != null) budget.string(key)
                return ContextPathComparison(key, key != null && key in builtIns)
            }
            val candidate = compare(query.candidate)
            val originals = query.originalPaths.map {
                currentCoroutineContext().ensureActive()
                compare(it)
            }
            currentCoroutineContext().ensureActive()
            ContextPathEvaluation.Evaluated(candidate, originals)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: PathObservationLimitExceeded) {
            ContextPathEvaluation.Failure(ContextPathEvaluationFailure.LimitExceeded)
        } catch (_: Exception) {
            ContextPathEvaluation.Failure(ContextPathEvaluationFailure.EnvironmentUnavailable)
        }
    }

    private fun expandDirectory(spelling: String, base: Path): Path {
        if ('\u0000' in spelling) throw PathQueryRejected(DirectoryQueryFailure.InvalidPath)
        return try {
            val prefix = "~$SystemPathSeparator"
            val path = when {
                spelling == "~" || spelling.startsWith(prefix) -> {
                    val home = userHome?.takeIf { absolutePathOrNull(it.toString()) != null }
                        ?: throw PathQueryRejected(DirectoryQueryFailure.HomeDirectoryUnavailable)
                    if (spelling == "~") home else Path(home, spelling.substring(2))
                }
                else -> Path(spelling)
            }
            anchorDirectory(path, base)
        } catch (_: IllegalArgumentException) {
            throw PathQueryRejected(DirectoryQueryFailure.InvalidPath)
        }
    }

    private companion object {
        // Half the existing 16 MiB business frame budget leaves room for the RPC envelope.
        const val DefaultObservationBudgetBytes: Long = 8L * 1024 * 1024
    }
}

private fun launchBaseOrNull(spelling: String): Path? {
    val path = absolutePathOrNull(spelling) ?: return null
    if (windowsPathRuntime && !spelling.hasWindowsDriveRoot() && !spelling.isWindowsUnc()) return null
    return path
}

private fun anchorDirectory(path: Path, base: Path): Path {
    val spelling = path.toString()
    if (windowsPathRuntime) {
        val baseSpelling = base.toString()
        if (spelling.hasWindowsDrivePrefix() && !spelling.hasWindowsDriveRoot()) {
            // Other-drive current directories are implicit process state, not the captured base.
            if (!baseSpelling.hasWindowsDrivePrefix() ||
                !spelling.take(2).equals(baseSpelling.take(2), ignoreCase = true)
            ) throw PathQueryRejected(DirectoryQueryFailure.InvalidPath)
            return Path(base, spelling.substring(2))
        }
        if (spelling.firstOrNull()?.isWindowsSeparator() == true && !spelling.isWindowsUnc()) {
            if (!baseSpelling.hasWindowsDriveRoot()) throw PathQueryRejected(DirectoryQueryFailure.InvalidPath)
            return Path(baseSpelling.take(2) + spelling)
        }
    }
    return if (path.isAbsolute) path else Path(base, spelling)
}

private val windowsPathRuntime: Boolean = Path("C:\\").isAbsolute
private fun Char.isWindowsSeparator(): Boolean = this == '/' || this == '\\'
private fun String.hasWindowsDrivePrefix(): Boolean =
    length >= 2 && this[1] == ':' && (this[0] in 'A'..'Z' || this[0] in 'a'..'z')

private fun String.hasWindowsDriveRoot(): Boolean =
    hasWindowsDrivePrefix() && length >= 3 && this[2].isWindowsSeparator()

private fun String.isWindowsUnc(): Boolean =
    length >= 2 && this[0].isWindowsSeparator() && this[1].isWindowsSeparator()

private fun absolutePathOrNull(spelling: String): Path? {
    if (spelling.isBlank() || '\u0000' in spelling) return null
    return try {
        Path(spelling).takeIf { it.isAbsolute }
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun contextLexicalKey(spelling: String, home: Path): String? {
    if (spelling.isBlank() || '$' in spelling || '\u0000' in spelling) return null
    return try {
        when {
            spelling == "~" -> home
            spelling.startsWith("~/") -> Path(home, spelling.substring(2))
            else -> Path(spelling).takeIf { it.isAbsolute }
        }?.toString()
    } catch (_: IllegalArgumentException) {
        null
    }
}

private class PathQueryRejected(val reason: DirectoryQueryFailure) : Exception()
private class PathObservationLimitExceeded : Exception()

/**
 * Conservative JSON upper bound (six bytes per UTF-16 code unit), including row overhead.
 * This is a per-observation work/wire budget, not a persisted-settings or path-length restriction.
 */
private class PathObservationBudget(private var remaining: Long) {
    init {
        charge(512)
    }

    fun string(value: String) {
        charge(6L * value.length + 2)
    }

    fun entry() {
        charge(128)
    }

    private fun charge(bytes: Long) {
        if (bytes > remaining) throw PathObservationLimitExceeded()
        remaining -= bytes
    }
}
