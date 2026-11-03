package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.migration.prepareKodexHome
import io.github.stream29.kodex.app.test.deleteTestDirectory
import io.github.stream29.kodex.cli.settings.UnifiedSettingsStore
import io.github.stream29.kodex.rpc.models.NotificationHook
import io.github.stream29.kodex.rpc.models.NotificationHookType
import io.github.stream29.kodex.rpc.server.defaultBackendSettings
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem as Fs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val immediateFrontendErrorHookTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("the first warning is admitted before render can emit it") {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            val root = Path(SystemTemporaryDirectory, "kodex-immediate-hook-${Random.nextLong()}")
            val launch = Path(root, "frontend-launch")
            try {
                val home = prepareKodexHome(root)
                try {
                    Fs.createDirectories(launch)
                    val settings = UnifiedSettingsStore.open(root, defaultBackendSettings())
                    val before = settings.current.frontend
                    val hook = NotificationHook(
                        "capture-immediate",
                        setOf(NotificationHookType.StopUnhandledError),
                        "cat > immediate-hook.json",
                    )
                    assertTrue(settings.compareAndSetFrontend(before, before.copy(hooks = listOf(hook))))
                    withKodexApplication(
                        home, launch, Path(root, "codex"), Path(root, "agents"),
                        createClient = { applicationClient() }, createLoginClient = { ApplicationLoginClient },
                    ) { app ->
                        app.reportWarning("first-warning")
                        val output = Path(root, "immediate-hook.json")
                        withTimeout(10.seconds) {
                            while (!Fs.exists(output) || Fs.readString(output).isEmpty()) delay(10)
                        }
                        val payload = Json.parseToJsonElement(Fs.readString(output)).jsonObject
                        assertEquals("unhandled_error", payload.getValue("type").jsonPrimitive.content)
                        assertEquals("first-warning", payload.getValue("message").jsonPrimitive.content)
                        assertFalse(Fs.exists(Path(launch, "immediate-hook.json")))
                    }
                } finally { withContext(NonCancellable) { home.closeAndJoin() } }
            } finally { withContext(NonCancellable) { deleteTestDirectory(root) } }
        }
    }
}
