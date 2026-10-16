package io.github.stream29.kodex.app.migration.v0_4_5

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

val migrateToV0_4_5Test by testSuite {
    testFixture {
        Path(SystemTemporaryDirectory, "kodex-migration-0.4.5-${Random.nextLong()}").also {
            SystemCoroutineFileSystem.createDirectories(it)
        }
    } closeWith {
        deleteRecursively(this)
    } asParameterForEach {
        test("wraps legacy token counts and preserves structured records") { home ->
            val timeline = Path(home, "sessions", "0", "token-count")
            SystemCoroutineFileSystem.createDirectories(timeline)
            SystemCoroutineFileSystem.writeString(Path(timeline, "0.json"), "12")
            SystemCoroutineFileSystem.writeString(
                Path(timeline, "1.json"),
                """{"kind":"response","total_tokens":34}""",
            )
            SystemCoroutineFileSystem.writeString(Path(timeline, "latest.json"), "1")

            migrateToV0_4_5(home, SystemCoroutineFileSystem)

            assertEquals(
                """{"kind":"legacy","total_tokens":12}""",
                SystemCoroutineFileSystem.readString(Path(timeline, "0.json")),
            )
            assertEquals(
                """{"kind":"response","total_tokens":34}""",
                SystemCoroutineFileSystem.readString(Path(timeline, "1.json")),
            )
            assertEquals("1", SystemCoroutineFileSystem.readString(Path(timeline, "latest.json")))
        }

        test("blocks ambiguous token-count records without rewriting them") { home ->
            val timeline = Path(home, "sessions", "0", "token-count")
            SystemCoroutineFileSystem.createDirectories(timeline)
            val record = Path(timeline, "0.json")
            SystemCoroutineFileSystem.writeString(record, """{"total_tokens":12}""")

            assertFailsWith<IOException> {
                migrateToV0_4_5(home, SystemCoroutineFileSystem)
            }
            assertEquals("""{"total_tokens":12}""", SystemCoroutineFileSystem.readString(record))
        }
    }
}

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { child -> deleteRecursively(child) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
