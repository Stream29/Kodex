package io.github.stream29.kodex.app.migration.v0_4_15

import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import de.infix.testBalloon.framework.core.testSuite
import de.infix.testBalloon.framework.core.TestCompartment
import io.github.stream29.kodex.app.migration.v0_4_7.SettingsCodec
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val migrateToV0_4_15Test by testSuite(compartment = { TestCompartment.RealTime }) {
    test("merge preserves Ultrafast strings and splits frontend error hooks") {
        fixture { home ->
            SystemCoroutineFileSystem.writeString(Path(home, "settings.backend.yml"), "new_session:\n  service_tier: ultrafast\n")
            SystemCoroutineFileSystem.writeString(Path(home, "settings.frontend.cli.yml"), """
                hooks:
                  - name: notify
                    command: "0001"
                    types: [stop_assistant_message, stop_unhandled_error]
            """.trimIndent())
            migrateToV0_4_15(home, SystemCoroutineFileSystem)
            val root = SettingsCodec.parse(SystemCoroutineFileSystem.readString(Path(home, "settings.yml")))
            assertEquals("ultrafast", root.get<YamlMap>("backend")!!.get<YamlMap>("new_session")!!.get<YamlScalar>("service_tier")!!.content)
            val hook = root.get<YamlMap>("frontend")!!.get<YamlList>("hooks")!!.items.single() as YamlMap
            assertEquals("0001", hook.get<YamlScalar>("command")!!.content)
            assertEquals(listOf("stop_unhandled_error"), hook.get<YamlList>("types")!!.items.map { (it as YamlScalar).content })
            assertFalse(SystemCoroutineFileSystem.exists(Path(home, "settings.backend.yml")))
            assertFalse(SystemCoroutineFileSystem.exists(Path(home, "settings.frontend.cli.yml")))
        }
    }
    test("legal single-source homes merge without requiring the missing peer file") {
        for (name in listOf("settings.backend.yml", "settings.frontend.cli.yml")) fixture { home ->
            SystemCoroutineFileSystem.writeString(Path(home, name), "{}")
            migrateToV0_4_15(home, SystemCoroutineFileSystem)
            assertTrue(SystemCoroutineFileSystem.exists(Path(home, "settings.yml")))
            migrateToV0_4_15(home, SystemCoroutineFileSystem)
        }
    }
    test("no settings does not create a defaults file") {
        fixture { home ->
            migrateToV0_4_15(home, SystemCoroutineFileSystem)
            assertFalse(SystemCoroutineFileSystem.exists(Path(home, "settings.yml")))
        }
    }
    test("conflicting target leaves both source and target untouched") {
        fixture { home ->
            val source = "auth_source: codex"
            val target = "backend:\n  auth_source: kodex\nfrontend: {}\n"
            SystemCoroutineFileSystem.writeString(Path(home, "settings.backend.yml"), source)
            SystemCoroutineFileSystem.writeString(Path(home, "settings.yml"), target)
            assertFailsWith<Exception> { migrateToV0_4_15(home, SystemCoroutineFileSystem) }
            assertEquals(source, SystemCoroutineFileSystem.readString(Path(home, "settings.backend.yml")))
            assertEquals(target, SystemCoroutineFileSystem.readString(Path(home, "settings.yml")))
        }
    }
}

private suspend fun fixture(block: suspend (Path) -> Unit) {
    val home = Path(SystemTemporaryDirectory, "kodex-merge-015-${Random.nextLong()}")
    SystemCoroutineFileSystem.createDirectories(home, mustCreate = true)
    try { block(home) }
    finally { withContext(NonCancellable) { remove(home) } }
}
private suspend fun remove(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) SystemCoroutineFileSystem.list(path).forEach { remove(it) }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
