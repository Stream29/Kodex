package io.github.stream29.kodex.cli.settings

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.assertEquals
import kotlin.test.assertTrue

val kodexSettingsPermissionsTest by testSuite(compartment = { TestCompartment.RealTime }) {
    for (backend in listOf(true, false)) {
        test("${if (backend) "backend" else "frontend"} snapshot and temporary use owner-only POSIX permissions") {
            val root = Files.createTempDirectory("kodex-settings-permissions")
            try {
                val settingsDirectory = root.resolve("kodex")
                val permissions = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
                var checkedTemporary = false
                val fileSystem = FaultingSettingsFileSystem()
                fileSystem.afterWrite = {
                    if (Files.getFileStore(settingsDirectory).supportsFileAttributeView("posix")) {
                        Files.list(settingsDirectory).use { files ->
                            val temporary = files.filter { it.fileName.toString().endsWith(".tmp") }.toList()
                            assertEquals(1, temporary.size)
                            assertEquals(permissions, Files.getPosixFilePermissions(temporary.single()))
                            checkedTemporary = true
                        }
                    }
                }
                val settingsPath = if (backend) {
                    val store = openBackendSettings(
                        Path(settingsDirectory.toString()), backendDefaults(), fileSystem,
                    )
                    store.update { it }
                    store.settingsPath
                } else {
                    val store = openCliFrontendSettings(
                        Path(settingsDirectory.toString()), fileSystem = fileSystem,
                    )
                    store.update { it }
                    store.settingsPath
                }

                if (!Files.getFileStore(settingsDirectory).supportsFileAttributeView("posix")) {
                    return@test
                }
                assertTrue(checkedTemporary)
                assertEquals(
                    permissions,
                    Files.getPosixFilePermissions(java.nio.file.Path.of(settingsPath.toString())),
                )
                Files.list(settingsDirectory).use { files ->
                    assertEquals(listOf(settingsPath.name), files.map { it.fileName.toString() }.toList())
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }
}
