@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.stream29.kodex.utils.processclient

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.posix.F_DUPFD
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.fcntl
import platform.posix.open
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val processDescriptorIsolationTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("direct child does not inherit an unrelated non-CLOEXEC descriptor") {
        val source = open("/dev/null", O_RDONLY)
        assertTrue(source >= 0)
        try {
            val probe = fcntl(source, F_DUPFD, 123)
            assertTrue(probe >= 123)
            try {
                val client = CoroutineScope(currentCoroutineContext()).ProcessClient()
                try {
                    val process = client.start(ProcessCommand(
                        executable = "/bin/sh",
                        arguments = listOf("-c", "test ! -e /dev/fd/$probe"),
                    ))
                    try {
                        process.stdin.close()
                        assertEquals(0, withTimeout(5.seconds) { process.exitCode.await() })
                    } finally {
                        process.closeAndJoin()
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

    test("closing one child's stdin reaches EOF while a sibling remains alive") {
        val client = CoroutineScope(currentCoroutineContext()).ProcessClient()
        val command = ProcessCommand(executable = "/bin/cat")
        try {
            val first = client.start(command)
            try {
                val sibling = client.start(command)
                try {
                    first.stdin.close()
                    assertEquals(0, withTimeout(5.seconds) { first.exitCode.await() })
                    assertTrue(!sibling.exitCode.isCompleted, "The sibling must still be running when EOF is observed.")
                } finally {
                    withContext(NonCancellable) { sibling.closeAndJoin() }
                }
            } finally {
                withContext(NonCancellable) { first.closeAndJoin() }
            }
        } finally {
            client.close()
        }
    }
}
