@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import platform.posix.chmod
import platform.posix.kill
import platform.posix.stat
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

val linuxChromeAppTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("default resolves to Chrome and system remains explicit") {
        assertEquals(LocalAppEngine.Chrome, resolveLocalAppEngine(LocalAppEngine.Default))
        assertEquals(LocalAppEngine.Chrome, resolveLocalAppEngine(LocalAppEngine.Chrome))
        assertEquals(LocalAppEngine.System, resolveLocalAppEngine(LocalAppEngine.System))
    }

    test("a Wayland-only environment receives a session hint without overriding explicit choices") {
        assertEquals(mapOf("XDG_SESSION_TYPE" to "wayland"), chromeSessionEnvironment("wayland-0", null, null))
        assertTrue(chromeSessionEnvironment("wayland-0", ":0", null).isEmpty())
        assertTrue(chromeSessionEnvironment("wayland-0", null, "x11").isEmpty())
        assertTrue(chromeSessionEnvironment(null, null, null).isEmpty())
    }

    test("isolated Chrome profile and its child are cleaned after the window process exits") {
        chromeFixture { root, executable ->
            val profileMarker = Path(root, "profile")
            val release = Path(root, "release")
            val childMarker = Path(root, "child")
            writeExecutable(executable, """
                #!/bin/sh
                for arg in "${'$'}@"; do
                    case "${'$'}arg" in --user-data-dir=*) profile=${'$'}{arg#*=};; esac
                done
                printf '%s' "${'$'}profile" > "$profileMarker"
                count=0
                while [ ! -f "$release" ] && [ "${'$'}count" -lt 100 ]; do
                    count=${'$'}((count + 1))
                    sleep 0.05
                done
                sleep 30 &
                printf '%s' "${'$'}!" > "$childMarker"
                exit 0
            """.trimIndent())
            coroutineScope {
                val running = async { runLinuxChromeApp("http://127.0.0.1/private", { false }, executable.toString()) }
                waitForFile(profileMarker)
                val profile = SystemCoroutineFileSystem.readString(profileMarker)
                assertTrue(profile.startsWith("/tmp/kodex-app-chrome-"))
                assertEquals(0x1c0, memScoped {
                    val attributes = alloc<stat>()
                    check(stat(profile, attributes.ptr) == 0)
                    attributes.st_mode.toInt() and 0x1ff
                })
                SystemCoroutineFileSystem.writeString(release, "continue")
                assertEquals(0, withTimeout(10_000) { running.await() })
                val child = SystemCoroutineFileSystem.readString(childMarker).toInt()
                assertFalse(SystemCoroutineFileSystem.exists(Path(profile)))
                assertTrue(kill(child, 0) != 0 || isZombie(child), "Owned Chrome descendant must not remain running.")
            }
        }
    }

    test("stop kills the owned Chrome group and deletes only its private profile") {
        chromeFixture { root, executable ->
            val profileMarker = Path(root, "profile")
            writeExecutable(executable, """
                #!/bin/sh
                for arg in "${'$'}@"; do
                    case "${'$'}arg" in --user-data-dir=*) profile=${'$'}{arg#*=};; esac
                done
                printf '%s' "${'$'}profile" > "$profileMarker"
                count=0
                while [ "${'$'}count" -lt 10 ]; do
                    count=${'$'}((count + 1))
                    sleep 1
                done
            """.trimIndent())
            val stop = MutableStateFlow(false)
            coroutineScope {
                val running = async { runLinuxChromeApp("http://127.0.0.1/private", { stop.value }, executable.toString()) }
                waitForFile(profileMarker)
                val profile = SystemCoroutineFileSystem.readString(profileMarker)
                stop.value = true
                assertEquals(130, withTimeout(10_000) { running.await() })
                assertFalse(SystemCoroutineFileSystem.exists(Path(profile)))
            }
        }
    }

    test("missing Chrome is reported instead of falling back to a different engine") {
        chromeFixture { root, _ ->
            val failure = assertFailsWith<IllegalStateException> {
                runLinuxChromeApp("http://127.0.0.1/private", { false }, Path(root, "missing").toString())
            }
            assertTrue(failure.message.orEmpty().contains("not found"))
        }
    }
}

private suspend fun chromeFixture(block: suspend (Path, Path) -> Unit) {
    val root = Path(SystemTemporaryDirectory, "kodex-chrome-test-${Random.nextLong().toULong()}")
    SystemCoroutineFileSystem.createDirectories(root, mustCreate = true)
    check(chmod(root.toString(), 0x1c0.convert()) == 0)
    try {
        block(root, Path(root, "fake-chrome"))
    } finally {
        SystemCoroutineFileSystem.list(root).forEach { SystemCoroutineFileSystem.delete(it, mustExist = false) }
        SystemCoroutineFileSystem.delete(root, mustExist = false)
    }
}

private suspend fun writeExecutable(path: Path, script: String) {
    SystemCoroutineFileSystem.writeString(path, script + "\n", mustCreate = true)
    check(chmod(path.toString(), 0x1c0.convert()) == 0)
}

private suspend fun waitForFile(path: Path) {
    withTimeout(10_000) {
        while (!SystemCoroutineFileSystem.exists(path) || SystemCoroutineFileSystem.readString(path).isEmpty()) delay(20)
    }
}

private suspend fun isZombie(pid: Int): Boolean =
    runCatching { SystemCoroutineFileSystem.readString(Path("/proc/$pid/stat")).substringAfterLast(") ").startsWith("Z ") }
        .getOrDefault(false)
