package io.github.stream29.kodex.app.migration.v0_4_15

import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlPath
import com.charleskorn.kaml.YamlScalar
import io.github.stream29.kodex.app.migration.v0_4_7.SettingsCodec
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path

/** New checkpoint; uses the frozen split schema, never a mutable current business model. */
internal suspend fun migrateToV0_4_15(home: Path, fileSystem: CoroutineFileSystem) {
    val backendPath = Path(home, "settings.backend.yml")
    val frontendPath = Path(home, "settings.frontend.cli.yml")
    val target = Path(home, "settings.yml")
    suspend fun read(path: Path): YamlMap? {
        if (!fileSystem.exists(path)) return null
        return try { SettingsCodec.parse(fileSystem.readString(path)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw IOException("Invalid settings during 0.4.15 migration: $path") }
    }
    try {
        val sourceBackend = read(backendPath)?.let(::normalizeBackend)
        val sourceFrontend = read(frontendPath)?.let(SettingsCodec::frontend)
        val published = read(target)
        if (published == null && sourceBackend == null && sourceFrontend == null) return
        val previousBackend = published?.get<YamlMap>("backend")
        val previousFrontend = published?.get<YamlMap>("frontend")
        require(published == null || previousBackend != null && previousFrontend != null)
        if (previousBackend != null && previousFrontend != null) {
            normalizeBackend(previousBackend)
            val ttl = previousBackend.get<YamlScalar>("daemon_ttl_seconds")?.toLong() ?: 300
            require(ttl == -1L || ttl > 0)
            previousBackend.get<YamlList>("hooks")?.let { hooks ->
                SettingsCodec.frontend(merge(emptyMap(), "hooks", hooks))
            }
            val checkedFront = SettingsCodec.frontend(previousFrontend)
            checkedFront.get<YamlList>("hooks")?.items.orEmpty().forEach { item ->
                require((item as YamlMap).get<YamlList>("types")?.items?.all {
                    (it as YamlScalar).content == "stop_unhandled_error"
                } == true)
            }
        }
        val front = sourceFrontend ?: emptyMap()
        val sourceHooks = front.get<YamlList>("hooks")?.items.orEmpty()
        val errorHooks = sourceHooks.mapNotNull { node ->
            val hook = node as YamlMap
            val types = hook.get<YamlList>("types") ?: error("Hook types missing.")
            if (types.items.none { (it as YamlScalar).content == "stop_unhandled_error" }) null
            else merge(hook, "types", YamlList(listOf(YamlScalar("stop_unhandled_error", YamlPath.root)), YamlPath.root))
        }
        val back = sourceBackend ?: emptyMap()
        val backend = if (published != null) requireNotNull(previousBackend) else
            merge(back, "hooks", YamlList(sourceHooks, YamlPath.root))
        val frontend = if (published != null) requireNotNull(previousFrontend) else
            merge(front, "hooks", YamlList(errorHooks, YamlPath.root))

        // Reentry: check each still-existing source against its corresponding target part.
        // A removed source must not reset a successfully published target section to defaults.
        if (sourceBackend != null && previousBackend != null) {
            require(normalizeBackend(previousBackend).equivalentContentTo(sourceBackend))
        }
        if (sourceFrontend != null && previousFrontend != null) {
            val expected = merge(sourceFrontend, "hooks", YamlList(errorHooks, YamlPath.root))
            require(SettingsCodec.frontend(previousFrontend).equivalentContentTo(expected))
            require(backend.get<YamlList>("hooks")?.items == sourceHooks ||
                backend.get<YamlList>("hooks")?.items?.map { SettingsCodec.encode(it as YamlMap) } ==
                sourceHooks.map { SettingsCodec.encode(it as YamlMap) })
        }
        if (published == null) {
            val result = YamlMap(linkedMapOf(
                YamlScalar("backend", YamlPath.root) to backend,
                YamlScalar("frontend", YamlPath.root) to frontend,
            ), YamlPath.root)
            val temporary = Path(home, ".settings.migration-0.4.15.tmp")
            try {
                fileSystem.delete(temporary, mustExist = false)
                fileSystem.writePrivateString(temporary, SettingsCodec.encode(result), mustCreate = true)
                fileSystem.atomicMove(temporary, target)
            } finally {
                withContext(NonCancellable) { fileSystem.delete(temporary, mustExist = false) }
            }
        }
        fileSystem.delete(backendPath, mustExist = false)
        fileSystem.delete(frontendPath, mustExist = false)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw IOException("Unified settings migration 0.4.15 could not complete; files were not replaced to resolve a conflict.")
    }
}

private fun emptyMap(): YamlMap = YamlMap(emptyMap<YamlScalar, YamlNode>(), YamlPath.root)
private fun merge(map: YamlMap, name: String, value: YamlNode): YamlMap =
    YamlMap(map.entries.filterKeys { it.content != name } + (YamlScalar(name, YamlPath.root) to value), YamlPath.root)

/** 0.4.14 added Ultrafast; extend the frozen source schema without modifying its old entry. */
private fun normalizeBackend(map: YamlMap): YamlMap {
    val defaults = map.get<YamlMap>("new_session")
    val tier = defaults?.get<YamlScalar>("service_tier")?.content
    if (tier != "ultrafast") return SettingsCodec.backend(map)
    val compatible = merge(map, "new_session", merge(requireNotNull(defaults), "service_tier", YamlScalar("default", YamlPath.root)))
    val normalized = SettingsCodec.backend(compatible)
    return merge(normalized, "new_session", merge(requireNotNull(normalized.get<YamlMap>("new_session")),
        "service_tier", YamlScalar("ultrafast", YamlPath.root)))
}
