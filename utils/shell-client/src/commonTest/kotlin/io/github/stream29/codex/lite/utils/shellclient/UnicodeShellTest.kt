package io.github.stream29.codex.lite.utils.shellclient

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private suspend fun unicodeShellTestRoot(): Path =
    Path(SystemTemporaryDirectory, "codex-lite-shell-${Random.nextLong()}").also {
        SystemCoroutineFileSystem.createDirectories(it)
    }

private suspend fun deleteUnicodeShellTestRoot(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        for (child in SystemCoroutineFileSystem.list(path)) {
            deleteUnicodeShellTestRoot(child)
        }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}

private suspend fun ProcessSession.closeAndAwaitUnicodeShellCompletion() {
    close()
    withTimeout(10.seconds) {
        scope.coroutineContext[Job]?.join()
    }
}

private fun ByteArray.containsSequence(sequence: ByteArray): Boolean =
    sequence.isEmpty() || indices.any { start ->
        sequence.indices.all { offset -> getOrNull(start + offset) == sequence[offset] }
    }

val unicodeShellTest by testSuite(
    compartment = { TestCompartment.RealTime },
) {
    testFixture { unicodeShellTestRoot() } closeWith { deleteUnicodeShellTestRoot(this) } asParameterForEach {
        test("preserves Unicode commands and working directories for pipes and PTYs") { root ->
            val workingDirectory = Path(root, "工作目录-Русский-日本語")
            val markerFileName = "结果-данные.bin"
            val marker = Path(workingDirectory, markerFileName)
            val content = "中文 / русский / 日本語 / C:\\Windows\\Temp"
            val expected = content.encodeToByteArray()
            SystemCoroutineFileSystem.createDirectories(workingDirectory)

            for (tty in listOf(false, true)) {
                val probe = unicodeProbeProcessCommand(markerFileName, content)
                val client = ShellClient()
                try {
                    val session = withTimeout(10.seconds) {
                        client.start(
                            ShellProcessCommand(
                                command = probe.command,
                                workingDirectory = workingDirectory,
                                shell = probe.shell,
                                tty = tty,
                            ),
                        )
                    }
                    try {
                        assertEquals(0, withTimeout(10.seconds) { session.exitCode.await() })
                        val output = session.stdout.drain().renderedBytes()
                        if (tty) {
                            assertTrue(output.containsSequence(expected))
                        } else {
                            assertContentEquals(expected, output)
                        }
                        assertContentEquals(expected, SystemCoroutineFileSystem.readBytes(marker))
                    } finally {
                        session.closeAndAwaitUnicodeShellCompletion()
                    }
                } finally {
                    client.close()
                }
            }
        }

        test("resolves a shell from a Unicode preferred path") { root ->
            val preferredDirectory = Path(root, "解析-Русский-日本語")
            val preferredPath = Path(preferredDirectory, "cmd.exe")
            SystemCoroutineFileSystem.createDirectories(preferredDirectory)
            assertTrue(SystemCoroutineFileSystem.exists(preferredDirectory))
            SystemCoroutineFileSystem.writeBytes(preferredPath, byteArrayOf(0))

            assertEquals(
                expected = Shell(type = ShellType.Cmd, path = preferredPath),
                actual = Shell.resolve(type = ShellType.Cmd, preferredPath = preferredPath),
            )
        }
    }
}
