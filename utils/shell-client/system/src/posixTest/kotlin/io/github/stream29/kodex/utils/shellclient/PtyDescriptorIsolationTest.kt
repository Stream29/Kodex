@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.stream29.kodex.utils.shellclient

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import platform.posix.F_DUPFD
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.fcntl
import platform.posix.open
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val ptyDescriptorIsolationTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("PTY child does not inherit an unrelated non-CLOEXEC descriptor") {
        val source = open("/dev/null", O_RDONLY)
        assertTrue(source >= 0)
        try {
            val probe = fcntl(source, F_DUPFD, 123)
            assertTrue(probe >= 123)
            try {
                val client = CoroutineScope(currentCoroutineContext()).ShellClient()
                try {
                    val process = client.start(ShellProcessCommand(
                        command = "test ! -e /dev/fd/$probe",
                        shell = Shell(type = ShellType.Sh, path = Path("/bin/sh")),
                        tty = true,
                    ))
                    try {
                        assertEquals(0, withTimeout(5.seconds) { process.exitCode.await() })
                    } finally {
                        process.close()
                    }
                } finally {
                    client.close()
                }
            } finally {
                close(probe)
            }
        } finally {
            close(source)
        }
    }
}
