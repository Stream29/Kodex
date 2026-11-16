package io.github.stream29.kodex.agentcontext.skill.filesystem

import io.github.stream29.kodex.agentcontext.contract.AgentContextSourcePlan
import io.github.stream29.kodex.agentcontext.prefix.skill.contract.AvailableSkill
import io.github.stream29.kodex.agentcontext.prefix.skill.contract.SkillScope
import io.github.stream29.kodex.agentcontext.prefix.skill.contract.SkillSource
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.FileFingerprint
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.readByteArray

internal data class SkillWarning(
    val source: Path,
    val message: String,
)

internal data class ResolvedSkills(
    val skills: List<AvailableSkill>,
    val warnings: List<SkillWarning>,
)

internal class FileSystemSkillsResolver(
    private val fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    private val metadataCacheCapacity: Int = 512,
) {
    init {
        require(metadataCacheCapacity > 0) { "Skill metadata cache capacity must be positive." }
    }

    private val cacheMutex = Mutex()
    private val metadataCache = LinkedHashMap<Path, CachedSkillMetadata>()

    suspend fun resolve(plan: AgentContextSourcePlan): ResolvedSkills {
        val roots = buildList {
            plan.globalRoots.forEach { root ->
                globalSkillRoots(root).forEach { source -> addRootIfDirectory(source) }
            }
            plan.projectRoots.forEach { root ->
                projectSkillRoots(root).forEach { source -> addRootIfDirectory(source) }
            }
        }
        val warnings = mutableListOf<SkillWarning>()
        val discovered = mutableListOf<AvailableSkill>()
        roots.forEach { source ->
            scanRoot(source, discovered, warnings)
        }

        val unique = linkedMapOf<String, AvailableSkill>()
        discovered.forEach { skill ->
            unique[skill.name] = skill
        }
        val sorted = unique.values.sortedWith(
            compareBy<AvailableSkill> { skill -> skill.source.scope.rank }
                .thenBy { skill -> skill.name }
                .thenBy { skill -> skill.path.toString() },
        )
        return ResolvedSkills(
            skills = sorted,
            warnings = warnings.toList(),
        )
    }

    private fun globalSkillRoots(home: Path): List<SkillSource> = listOf(
        SkillSource(HostAuthorityId, SkillScope.User, Path(home, SkillsPath)),
    )

    private fun projectSkillRoots(root: Path): List<SkillSource> = listOf(
        SkillSource(HostAuthorityId, SkillScope.Repo, Path(root, SkillsPath)),
        SkillSource(HostAuthorityId, SkillScope.Repo, Path(root, ProjectAgentsSkillsPath)),
    )

    private suspend fun MutableList<SkillSource>.addRootIfDirectory(source: SkillSource) {
        if (fileSystem.metadataOrNull(source.root)?.isDirectory != true) return
        val resolved = fileSystem.resolve(source.root)
        add(source.copy(root = resolved))
    }

    private suspend fun scanRoot(
        source: SkillSource,
        discovered: MutableList<AvailableSkill>,
        warnings: MutableList<SkillWarning>,
    ) {
        val visited = mutableSetOf<Path>()
        var directoryCount = 0

        suspend fun scan(directory: Path, depth: Int) {
            if (depth > MaxScanDepth || directoryCount >= MaxDirectoriesPerRoot) return
            val resolvedDirectory = runCatchingIo(directory, warnings) { fileSystem.resolve(directory) }
                ?: return
            if (!visited.add(resolvedDirectory)) return
            directoryCount += 1
            val children = runCatchingIo(resolvedDirectory, warnings) {
                fileSystem.list(resolvedDirectory).sortedBy(Path::toString)
            } ?: return
            children.firstOrNull { child ->
                child.name == SkillFileName && fileSystem.metadataOrNull(child)?.isRegularFile == true
            }?.let { path ->
                loadMetadata(source, path, warnings)?.let(discovered::add)
            }
            if (depth == MaxScanDepth) return
            children.forEach { child ->
                if (child.name.startsWith('.')) return@forEach
                if (fileSystem.metadataOrNull(child)?.isDirectory == true) {
                    scan(child, depth + 1)
                }
            }
        }

        scan(source.root, depth = 0)
        if (directoryCount >= MaxDirectoriesPerRoot) {
            warnings += SkillWarning(
                source = source.root,
                message = "Skill root exceeded the $MaxDirectoriesPerRoot-directory scan limit.",
            )
        }
    }

    private suspend fun loadMetadata(
        source: SkillSource,
        path: Path,
        warnings: MutableList<SkillWarning>,
    ): AvailableSkill? {
        val resolved = runCatchingIo(path, warnings) { fileSystem.resolve(path) } ?: return null
        repeat(MaxMetadataReadAttempts) {
            val before = runCatchingIo(resolved, warnings) { fileSystem.fingerprintOrNull(resolved) }
                ?: return null
            cachedMetadata(resolved, before)?.let { metadata ->
                return metadata.toAvailableSkill(source, resolved)
            }
            val contents = runCatchingIo(resolved, warnings) {
                fileSystem.readMetadataPrefix(resolved)
            } ?: return null
            val after = runCatchingIo(resolved, warnings) { fileSystem.fingerprintOrNull(resolved) }
                ?: return null
            if (before != after) return@repeat
            val frontmatter = parseFrontmatter(contents, resolved, warnings) ?: return null
            cacheMetadata(resolved, CachedSkillMetadata(after, frontmatter))
            return frontmatter.toAvailableSkill(source, resolved)
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

    private fun SkillFrontmatter.toAvailableSkill(source: SkillSource, path: Path): AvailableSkill =
        AvailableSkill(
            name = name,
            description = description,
            path = path,
            source = source,
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

private val SkillScope.rank: Int
    get() = when (this) {
        SkillScope.Repo -> 0
        SkillScope.User -> 1
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
