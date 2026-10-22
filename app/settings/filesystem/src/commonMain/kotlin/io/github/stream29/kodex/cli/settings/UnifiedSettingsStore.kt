@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.settings

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import io.github.stream29.kodex.rpc.models.BackendSettings
import io.github.stream29.kodex.rpc.models.CliFrontendSettings
import io.github.stream29.kodex.rpc.models.NotificationHookType
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path

public actual class UnifiedSettingsStore private constructor(
    public actual val path: Path,
    private val defaults: UnifiedSettings,
    initial: UnifiedSettings,
    private val fileSystem: CoroutineFileSystem,
) {
    private val mutex = Mutex()
    @kotlin.concurrent.Volatile
    private var snapshot = initial
    public actual val current: UnifiedSettings get() = snapshot
    private val backendProjection = MutableStateFlow(initial.backend)
    private val frontendProjection = MutableStateFlow(initial.frontend)
    public actual val backendSettings: StateFlow<BackendSettings> = backendProjection.asStateFlow()
    public actual val frontendSettings: StateFlow<CliFrontendSettings> = frontendProjection.asStateFlow()

    /** Called only under the store mutex; projections have no separate write path or Job. */
    private fun publish(value: UnifiedSettings) {
        snapshot = value
        backendProjection.value = value.backend
        frontendProjection.value = value.frontend
    }

    public actual val backendStore: BackendSettingsStore = object : BackendSettingsStore {
        override val settings: StateFlow<BackendSettings> get() = backendSettings
        override val settingsPath: Path get() = path
        override suspend fun reload(): BackendSettings {
            val expected = current
            val candidate = readCandidate()
            check(acceptReload(expected, candidate) { _, _ -> }) { "Settings changed during reload." }
            return candidate.backend
        }
        override suspend fun update(transform: (BackendSettings) -> BackendSettings): BackendSettings = mutex.withLock {
            val proposed = snapshot.copy(backend = transform(snapshot.backend))
            persist(proposed)
            proposed.backend
        }
        override suspend fun compareAndSet(
            expect: BackendSettings, update: BackendSettings,
            validate: (BackendSettings, BackendSettings) -> Unit,
        ): Boolean = mutex.withLock {
            val old = snapshot
            if (old.backend != expect) return@withLock false
            if (old.backend == update) return@withLock true
            validate(old.backend, update)
            persist(old.copy(backend = update))
            true
        }
    }

    public actual suspend fun compareAndSetFrontend(expect: CliFrontendSettings, update: CliFrontendSettings): Boolean =
        mutex.withLock {
            val old = snapshot
            if (old.frontend != expect) return@withLock false
            if (expect == update) return@withLock true
            validateFrontend(update)
            persist(old.copy(frontend = update))
            true
        }

    public actual suspend fun readCandidate(): UnifiedSettings = readUnified(path, defaults, fileSystem)

    public actual suspend fun acceptReload(
        expected: UnifiedSettings,
        candidate: UnifiedSettings,
        validate: (BackendSettings, BackendSettings) -> Unit,
    ): Boolean = mutex.withLock {
        if (snapshot != expected) return@withLock false
        validate(expected.backend, candidate.backend)
        validateFrontend(candidate.frontend)
        publish(candidate)
        true
    }

    private suspend fun persist(value: UnifiedSettings) {
        validateFrontend(value.frontend)
        val text = "backend:\n" + encodeBackendSettingsFile(value.backend).prependIndent("  ") +
            "\nfrontend:\n" + encodeCliFrontendSettingsFile(value.frontend).prependIndent("  ")
        writeSplitSettings(fileSystem, path, text)
        publish(value)
    }

    public actual companion object {
        public actual suspend fun open(
            home: Path,
            defaults: BackendSettings,
            fileSystem: CoroutineFileSystem,
        ): UnifiedSettingsStore {
            val path = Path(home, "settings.yml")
            val initialDefaults = UnifiedSettings(defaults, CliFrontendSettings())
            return UnifiedSettingsStore(path, initialDefaults, readUnified(path, initialDefaults, fileSystem), fileSystem)
        }
    }
}

private suspend fun readUnified(
    path: Path, defaults: UnifiedSettings, fileSystem: CoroutineFileSystem,
): UnifiedSettings {
    if (!fileSystem.exists(path)) return defaults
    try {
        val root = Yaml.default.parseToYamlNode(fileSystem.readString(path)) as? YamlMap
            ?: error("Settings root is not a mapping.")
        fun section(name: String): YamlMap? {
            val node = root.get<YamlNode>(name) ?: return null
            require(node is YamlMap) { "Settings section must be a mapping." }
            return node
        }
        val value = UnifiedSettings(
            section("backend")?.let { decodeBackendSettingsFile(it, defaults.backend) } ?: defaults.backend,
            section("frontend")?.let { decodeCliFrontendSettingsFile(it, defaults.frontend) } ?: defaults.frontend,
        )
        validateFrontend(value.frontend)
        return value
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Never propagate Kaml diagnostics containing command or MCP credential contents.
        throw IllegalArgumentException("Invalid unified settings YAML at $path.")
    }
}

private fun validateFrontend(value: CliFrontendSettings) {
    require(value.hooks.all { it.types == setOf(NotificationHookType.StopUnhandledError) }) {
        "Frontend Hooks may only select local errors."
    }
}
