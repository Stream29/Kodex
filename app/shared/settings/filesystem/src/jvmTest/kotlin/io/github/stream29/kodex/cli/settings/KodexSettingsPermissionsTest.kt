package io.github.stream29.kodex.cli.settings

import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals

class KodexSettingsPermissionsTest {
    @Test
    fun settingsSnapshotUsesOwnerOnlyPosixPermissions(): Unit = runBlocking {
        val root = Files.createTempDirectory("kodex-settings-permissions")
        try {
            val settingsDirectory = root.resolve("kodex")
            val codexDirectory = root.resolve("codex")
            val store = openGlobalSettings(
                settingsDirectory = Path(settingsDirectory.toString()),
                defaults = KodexGlobalSettings(codexHome = Path(codexDirectory.toString())),
            )

            store.update { it }

            if (!Files.getFileStore(settingsDirectory).supportsFileAttributeView("posix")) {
                return@runBlocking
            }
            assertEquals(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                ),
                Files.getPosixFilePermissions(settingsDirectory.resolve("settings.yml")),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
