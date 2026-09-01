package io.github.stream29.kodex.agentcontext.skill.filesystem

import io.github.stream29.kodex.agentcontext.contract.AgentContextSettings
import io.github.stream29.kodex.agentcontext.prefix.skill.contract.AvailableSkill
import io.github.stream29.kodex.agentcontext.prefix.skill.contract.SkillScope
import io.github.stream29.kodex.agentcontext.prefix.skill.contract.SkillSource
import io.github.stream29.kodex.agentcontext.prefix.skill.contract.SkillWarning
import io.github.stream29.kodex.agentcontext.skill.contract.ResolvedSkills
import io.github.stream29.kodex.agentcontext.skill.contract.SkillDocument
import io.github.stream29.kodex.agentcontext.skill.contract.SkillResourceResult
import io.github.stream29.kodex.agentcontext.skill.contract.SkillsResolver
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.FileFingerprint
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.readByteArray

/**
 * Filesystem resolver for configured global skills and the exact project
 * endpoints containing the requested cwd.
 *
 * The metadata cache lives for the lifetime of this resolver. Each [resolve]
 * reads the current [contextSettings], so changing either global home changes
 * the global skill roots without replacing the cache-owning resolver.
 */
public class FileSystemSkillsResolver(
    private val contextSettings: StateFlow<AgentContextSettings>,
    projectRootMarkers: List<String> = listOf(".git"),
    private val fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    private val metadataCacheCapacity: Int = DefaultMetadataCacheCapacity,
) : SkillsResolver {
    init {
        require(metadataCacheCapacity > 0) { "Skill metadata cache capacity must be positive." }
    }

    private val cacheMutex = Mutex()
    private val metadataCache = LinkedHashMap<Path, CachedSkillMetadata>()
    private val projectRootMarkers = projectRootMarkers.toList()

    override suspend fun resolve(cwd: Path): ResolvedSkills =
        resolve(cwd, contextSettings.value)

    /**
     * Resolves [cwd] against an already captured [context].
     *
     * This overload keeps a larger context-prefix capture internally
     * consistent while the public resolver continues observing its StateFlow.
     */
    public suspend fun resolve(
        cwd: Path,
        context: AgentContextSettings,
    ): ResolvedSkills {
        val warnings = mutableListOf<SkillWarning>()
        val discovered = mutableListOf<DiscoveredSkill>()
        discoverRoots(cwd, context.agentsHome, context.kodexHome).forEach { root ->
            scanRoot(root, discovered, warnings)
        }

        val unique = linkedMapOf<Path, DiscoveredSkill>()
        discovered.forEach { skill ->
            if (skill.available.path !in unique) unique[skill.available.path] = skill
        }
        val sorted = unique.values.sortedWith(
            compareBy<DiscoveredSkill> { skill -> skill.available.source.scope.rank }
                .thenBy { skill -> skill.available.name }
                .thenBy { skill -> skill.available.path.toString() },
        )
        return FileSystemResolvedSkills(
            skills = sorted.map(DiscoveredSkill::available),
            warnings = warnings.toList(),
            authorities = sorted.associate { skill -> skill.available to skill.authority },
        )
    }

    private suspend fun discoverRoots(
        workingDirectory: Path,
        configuredAgentsHome: Path,
        configuredKodexHome: Path,
    ): List<SkillRoot> {
        val cwd = fileSystem.resolve(workingDirectory)
        val agentsHome = fileSystem.resolveAllowingMissing(configuredAgentsHome)
        val kodexHome = fileSystem.resolveAllowingMissing(configuredKodexHome)
        val projectRoot = nearestProjectRoot(cwd)
        return buildList {
            globalSkillRoots(agentsHome).forEach { source -> addRootIfDirectory(source) }
            globalSkillRoots(kodexHome).forEach { source -> addRootIfDirectory(source) }
            projectRoot?.let { root ->
                projectSkillRoots(root).forEach { source -> addRootIfDirectory(source) }
            }
            projectSkillRoots(cwd).forEach { source -> addRootIfDirectory(source) }
        }.distinctBy { root -> root.source.root }
    }

    private fun globalSkillRoots(home: Path): List<SkillSource> = listOf(
        SkillSource(HostAuthorityId, SkillScope.User, Path(home, SkillsPath)),
    )

    private fun projectSkillRoots(root: Path): List<SkillSource> = listOf(
        SkillSource(HostAuthorityId, SkillScope.Repo, Path(root, SkillsPath)),
        SkillSource(HostAuthorityId, SkillScope.Repo, Path(root, ProjectAgentsSkillsPath)),
    )

    private suspend fun MutableList<SkillRoot>.addRootIfDirectory(source: SkillSource) {
        if (fileSystem.metadataOrNull(source.root)?.isDirectory != true) return
        val resolved = fileSystem.resolve(source.root)
        add(SkillRoot(source.copy(root = resolved), fileSystem))
    }

    private suspend fun scanRoot(
        root: SkillRoot,
        discovered: MutableList<DiscoveredSkill>,
        warnings: MutableList<SkillWarning>,
    ) {
        val visited = mutableSetOf<Path>()
        var directoryCount = 0

        suspend fun scan(directory: Path, depth: Int) {
            if (depth > MaxScanDepth || directoryCount >= MaxDirectoriesPerRoot) return
            val resolvedDirectory = runCatchingIo(directory, warnings) { root.fileSystem.resolve(directory) }
                ?: return
            if (!visited.add(resolvedDirectory)) return
            directoryCount += 1
            val children = runCatchingIo(resolvedDirectory, warnings) {
                root.fileSystem.list(resolvedDirectory).sortedBy(Path::toString)
            } ?: return
            children.firstOrNull { child ->
                child.name == SkillFileName && root.fileSystem.metadataOrNull(child)?.isRegularFile == true
            }?.let { path ->
                loadMetadata(root, path, warnings)?.let(discovered::add)
            }
            if (depth == MaxScanDepth) return
            children.forEach { child ->
                if (child.name.startsWith('.')) return@forEach
                if (root.fileSystem.metadataOrNull(child)?.isDirectory == true) {
                    scan(child, depth + 1)
                }
            }
        }

        scan(root.source.root, depth = 0)
        if (directoryCount >= MaxDirectoriesPerRoot) {
            warnings += SkillWarning(
                source = root.source.root,
                message = "Skill root exceeded the $MaxDirectoriesPerRoot-directory scan limit.",
            )
        }
    }

    private suspend fun loadMetadata(
        root: SkillRoot,
        path: Path,
        warnings: MutableList<SkillWarning>,
    ): DiscoveredSkill? {
        val resolved = runCatchingIo(path, warnings) { root.fileSystem.resolve(path) } ?: return null
        repeat(MaxMetadataReadAttempts) {
            val before = runCatchingIo(resolved, warnings) { root.fileSystem.fingerprintOrNull(resolved) }
                ?: return null
            cachedMetadata(resolved, before)?.let { metadata ->
                return metadata.toDiscoveredSkill(root, resolved)
            }
            val contents = runCatchingIo(resolved, warnings) {
                root.fileSystem.readMetadataPrefix(resolved)
            } ?: return null
            val after = runCatchingIo(resolved, warnings) { root.fileSystem.fingerprintOrNull(resolved) }
                ?: return null
            if (before != after) return@repeat
            val frontmatter = parseFrontmatter(contents, resolved, warnings) ?: return null
            cacheMetadata(resolved, CachedSkillMetadata(after, frontmatter))
            return frontmatter.toDiscoveredSkill(root, resolved)
        }
        warnings += SkillWarning(resolved, "Skill changed while its metadata was being read.")
        return null
    }

    private suspend fun cachedMetadata(
        path: Path,
        fingerprint: FileFingerprint,
    ): SkillFrontmatter? = cacheMutex.withLock {
        val cached = metadataCache[path] ?: return@withLock null
        if (cached.fingerprint != fingerprint) return@withLock null
        metadataCache.remove(path)
        metadataCache[path] = cached
        cached.frontmatter
    }

    private suspend fun cacheMetadata(path: Path, metadata: CachedSkillMetadata) {
        cacheMutex.withLock {
            metadataCache.remove(path)
            metadataCache[path] = metadata
            while (metadataCache.size > metadataCacheCapacity) {
                metadataCache.remove(metadataCache.keys.first())
            }
        }
    }

    private fun SkillFrontmatter.toDiscoveredSkill(root: SkillRoot, path: Path): DiscoveredSkill =
        DiscoveredSkill(
            available = AvailableSkill(
                name = name,
                description = description,
                path = path,
                source = root.source,
            ),
            authority = SkillAuthority(root.source.root, root.fileSystem),
        )

    private suspend fun <T> runCatchingIo(
        source: Path,
        warnings: MutableList<SkillWarning>,
        block: suspend () -> T,
    ): T? = try {
        block()
    } catch (failure: Throwable) {
        currentCoroutineContext().ensureActive()
        warnings += SkillWarning(source, failure.toString())
        null
    }

    private suspend fun nearestProjectRoot(cwd: Path): Path? {
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

}

private class FileSystemResolvedSkills(
    override val skills: List<AvailableSkill>,
    override val warnings: List<SkillWarning>,
    private val authorities: Map<AvailableSkill, SkillAuthority>,
) : ResolvedSkills {
    override suspend fun loadSkill(skill: AvailableSkill): SkillResourceResult<SkillDocument> {
        val authority = authorities[skill]
            ?: return SkillResourceResult.Failure(skill.path, "Skill is not part of this resolution.")
        return read(skill.path, authority) { contents -> SkillDocument(skill, contents.decodeToString()) }
    }

    override suspend fun readResource(
        skill: AvailableSkill,
        relativePath: Path,
    ): SkillResourceResult<ByteArray> {
        val authority = authorities[skill]
            ?: return SkillResourceResult.Failure(skill.path, "Skill is not part of this resolution.")
        if (relativePath.toString().isEmpty() || relativePath.isAbsolute || relativePath.containsParentTraversal()) {
            return SkillResourceResult.Failure(relativePath, "Skill resource path must stay relative to the skill.")
        }
        val skillDirectory = skill.path.parent
            ?: return SkillResourceResult.Failure(skill.path, "Skill path has no parent directory.")
        val resource = try {
            authority.fileSystem.resolve(Path(skillDirectory, relativePath.toString()))
        } catch (failure: Throwable) {
            currentCoroutineContext().ensureActive()
            return SkillResourceResult.Failure(relativePath, failure.toString())
        }
        if (!resource.isWithin(skillDirectory) || !resource.isWithin(authority.root)) {
            return SkillResourceResult.Failure(resource, "Skill resource escapes its source root.")
        }
        return read(resource, authority) { bytes -> bytes }
    }

    private suspend fun <T> read(
        path: Path,
        authority: SkillAuthority,
        transform: (ByteArray) -> T,
    ): SkillResourceResult<T> = try {
        SkillResourceResult.Success(transform(authority.fileSystem.readBytes(path)))
    } catch (failure: Throwable) {
        currentCoroutineContext().ensureActive()
        SkillResourceResult.Failure(path, failure.toString())
    }
}

private suspend fun CoroutineFileSystem.readMetadataPrefix(path: Path): String =
    useSource(path) { input ->
        val buffer = Buffer()
        var remaining = MaxMetadataByteCount
        while (remaining > 0L) {
            val read = input.readAtMostTo(buffer, minOf(remaining, MetadataReadSegmentByteCount))
            if (read == -1L) break
            remaining -= read
        }
        buffer.readByteArray().decodeToString()
    }

private data class SkillRoot(
    val source: SkillSource,
    val fileSystem: CoroutineFileSystem,
)

private data class SkillAuthority(
    val root: Path,
    val fileSystem: CoroutineFileSystem,
)

private data class DiscoveredSkill(
    val available: AvailableSkill,
    val authority: SkillAuthority,
)

private data class CachedSkillMetadata(
    val fingerprint: FileFingerprint,
    val frontmatter: SkillFrontmatter,
)

private data class SkillFrontmatter(
    val name: String,
    val description: String,
)

private fun parseFrontmatter(
    contents: String,
    source: Path,
    warnings: MutableList<SkillWarning>,
): SkillFrontmatter? {
    val lines = contents.lineSequence().toList()
    if (lines.firstOrNull()?.trim() != "---") {
        warnings += SkillWarning(source, "Missing YAML frontmatter delimited by ---.")
        return null
    }
    val closing = lines.indexOfFirstFrom(1) { line -> line.trim() == "---" }
    if (closing < 0) {
        warnings += SkillWarning(source, "Missing closing YAML frontmatter delimiter.")
        return null
    }
    val fields = mutableMapOf<String, String>()
    var index = 1
    while (index < closing) {
        val line = lines[index]
        val separator = line.indexOf(':')
        if (separator <= 0) {
            index += 1
            continue
        }
        val key = line.substring(0, separator).trim()
        val rawValue = line.substring(separator + 1).trim()
        if (rawValue == ">" || rawValue == "|") {
            val block = mutableListOf<String>()
            index += 1
            while (index < closing && lines[index].firstOrNull()?.isWhitespace() == true) {
                block += lines[index].trim()
                index += 1
            }
            fields[key] = if (rawValue == ">") block.joinToString(" ") else block.joinToString("\n")
            continue
        }
        fields[key] = rawValue.removeSurrounding("\"").removeSurrounding("'")
        index += 1
    }
    val name = fields["name"]
        ?.sanitizeSingleLine()
        ?.takeIf(String::isNotEmpty)
        ?: source.parent?.name?.sanitizeSingleLine().orEmpty().ifEmpty { "skill" }
    val description = fields["description"]?.sanitizeSingleLine().orEmpty()
    val invalid = when {
        name.length > MaxNameLength -> "Skill name exceeds $MaxNameLength characters."
        description.isEmpty() -> "Missing frontmatter field `description`."
        description.length > MaxDescriptionLength ->
            "Skill description exceeds $MaxDescriptionLength characters."

        else -> null
    }
    if (invalid != null) {
        warnings += SkillWarning(source, invalid)
        return null
    }
    return SkillFrontmatter(name, description)
}

private fun String.sanitizeSingleLine(): String =
    splitToSequence(Regex("\\s+"))
        .filter(String::isNotEmpty)
        .joinToString(" ")

private inline fun <T> List<T>.indexOfFirstFrom(start: Int, predicate: (T) -> Boolean): Int {
    for (index in start until size) if (predicate(this[index])) return index
    return -1
}

private fun Path.isWithin(root: Path): Boolean {
    var current: Path? = this
    while (current != null) {
        if (current == root) return true
        current = current.parent
    }
    return false
}

private fun Path.containsParentTraversal(): Boolean {
    var current: Path? = this
    while (current != null) {
        if (current.name == "..") return true
        current = current.parent
    }
    return false
}

private suspend fun CoroutineFileSystem.resolveAllowingMissing(path: Path): Path {
    if (metadataOrNull(path) != null) return resolve(path)
    val parent = path.parent
    val resolvedParent = if (parent == null) resolve(Path(".")) else resolveAllowingMissing(parent)
    return Path(resolvedParent, path.name)
}

private val SkillScope.rank: Int
    get() = when (this) {
        SkillScope.Repo -> 0
        SkillScope.User -> 1
        SkillScope.System -> 2
        SkillScope.Admin -> 3
    }

private const val SkillFileName: String = "SKILL.md"
private const val SkillsPath: String = "skills"
private const val ProjectAgentsSkillsPath: String = ".agents/skills"
private const val HostAuthorityId: String = "host"
private const val MaxScanDepth: Int = 6
private const val MaxDirectoriesPerRoot: Int = 2_000
private const val MaxNameLength: Int = 64
private const val MaxDescriptionLength: Int = 1_024
private const val MaxMetadataReadAttempts: Int = 2
private const val MaxMetadataByteCount: Long = 64 * 1024L
private const val MetadataReadSegmentByteCount: Long = 8 * 1024L
private const val DefaultMetadataCacheCapacity: Int = 512
