package io.github.stream29.kodex.cli.settings

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.rpc.models.BackendSettings
import io.github.stream29.kodex.rpc.models.CliFrontendSettings
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val settingsStoreInterfaceTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("original factories are statically typed to the single real store interfaces and reload before return") {
        val backendFactory: suspend (Path, BackendSettings, CoroutineFileSystem) -> BackendSettingsStore =
            ::openBackendSettings
        val frontendFactory: suspend (Path, CliFrontendSettings, CoroutineFileSystem) -> CliFrontendSettingsStore =
            ::openCliFrontendSettings
        withSplitSettingsDirectory { directory ->
            writeFixture(Path(directory, "settings.backend.yml"), "auth_source: kodex\n")
            writeFixture(Path(directory, "settings.frontend.cli.yml"), "new_line_key: enter\n")
            val backend: BackendSettingsStore =
                backendFactory(directory, backendDefaults(), SystemCoroutineFileSystem)
            val frontend: CliFrontendSettingsStore =
                frontendFactory(directory, CliFrontendSettings(), SystemCoroutineFileSystem)
            val backendFlow: StateFlow<BackendSettings> = backend.settings
            val frontendFlow: StateFlow<CliFrontendSettings> = frontend.settings
            assertEquals(KodexAuthSource.Kodex, backendFlow.value.authSource)
            assertEquals(NewLineKey.Enter, frontendFlow.value.newLineKey)
            assertEquals(Path(directory, "settings.backend.yml"), backend.settingsPath)
            assertEquals(Path(directory, "settings.frontend.cli.yml"), frontend.settingsPath)
            assertTrue(backend.compareAndSet(backendFlow.value, backendFlow.value))
            assertEquals(NewLineKey.Enter, frontend.reload().newLineKey)
        }
    }

    test("internal fault-injectable implementations implement the same contracts without compatibility wrappers") {
        withSplitSettingsDirectory { directory ->
            val fileSystem = FaultingSettingsFileSystem()
            val defaults = backendDefaults()
            val backend: BackendSettingsStore = FileSystemBackendSettingsStore(fileSystem, directory, defaults)
            val frontend: CliFrontendSettingsStore =
                FileSystemCliFrontendSettingsStore(fileSystem, directory, CliFrontendSettings())
            assertEquals(defaults, backend.settings.value)
            assertEquals(defaults, backend.reload())
            assertEquals(CliFrontendSettings(), frontend.reload())
            assertTrue(backend.compareAndSet(defaults, defaults))
            assertFalse(SystemCoroutineFileSystem.exists(directory))
            backend.update { it.copy(authSource = KodexAuthSource.Kodex) }
            frontend.update { it.copy(newLineKey = NewLineKey.Enter) }
            assertEquals(KodexAuthSource.Kodex, openBackendSettings(directory, defaults).settings.value.authSource)
            assertEquals(NewLineKey.Enter, openCliFrontendSettings(directory).settings.value.newLineKey)
        }
    }
}
