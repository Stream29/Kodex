package io.github.stream29.kodex.cli.settings

import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

val kodexSettingsPermissionsTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("settingsSnapshotUsesOwnerOnlyPosixPermissions") {
        val root = Files.createTempDirectory("kodex-settings-permissions")
        try {
            val settingsDirectory = root.resolve("kodex")
            val store = openGlobalSettings(
                settingsDirectory = Path(settingsDirectory.toString()),
                defaults = KodexGlobalSettings(),
            )

            store.update { it }

            if (!Files.getFileStore(settingsDirectory).supportsFileAttributeView("posix")) {
                return@test
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
