@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.stream29.kodex.utils.shellclient

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import platform.posix.F_DUPFD
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.fcntl
import platform.posix.open
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
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

    test("root exit flushes the PTY tail even when a descendant is still holding the slave") {
        val client = CoroutineScope(currentCoroutineContext()).ShellClient()
        try {
            // Ignore HUP before spawning so the descendant inherits it. The
            // bounded sleep keeps the slave open without leaving a lasting child.
            val process = client.start(ptyCommand("trap '' HUP; sleep 4 & printf 'root-tail\\n'; exit 19"))
            try {
                withTimeout(2.seconds) {
                    assertEquals(19, process.exitCode.await())
                    assertTrue("root-tail" in process.stdout.read(offset = 0uL).renderedBytes().decodeToString())
                    assertTrue("root-tail" in process.standardOutput.read(offset = 0uL).renderedBytes().decodeToString())
                    assertTrue(process.standardError.read(offset = 0uL).isEmpty)
                    requireNotNull(process.scope.coroutineContext[Job]).join()
                }
            } finally {
                process.close()
            }
        } finally {
            client.close()
        }
    }

    test("a PTY delivers explicit EOT without closing the shared output fd") {
        val client = CoroutineScope(currentCoroutineContext()).ShellClient()
        try {
            val process = client.start(ptyCommand("IFS= read -r line || printf 'received-eot\\n'"))
            try {
                withTimeout(5.seconds) {
                    process.stdin.send("\u0004")
                    assertEquals(0, process.exitCode.await())
                    assertTrue("received-eot" in process.stdout.read(offset = 0uL).renderedBytes().decodeToString())
                }
            } finally {
                process.close()
            }
        } finally {
            client.close()
        }
    }

    test("closing a PTY cancels blocked input without waiting for its write to complete") {
        val client = CoroutineScope(currentCoroutineContext()).ShellClient()
        try {
            val process = client.start(ptyCommand("stty raw -echo && printf ready && exec sleep 20"))
            try {
                withTimeout(5.seconds) {
                    while ("ready" !in process.stdout.read(offset = 0uL).renderedBytes().decodeToString()) {
                        delay(10.milliseconds)
                    }
                    coroutineScope {
                        val sender = async(start = CoroutineStart.UNDISPATCHED) {
                            process.stdin.send("x".repeat(1_048_576))
                        }
                        delay(100.milliseconds)
                        assertFalse(sender.isCompleted)
                        process.close()
                        assertFailsWith<CancellationException> { sender.await() }
                        assertTrue(process.exitCode.await() != 0)
                        requireNotNull(process.scope.coroutineContext[Job]).join()
                    }
                }
            } finally {
                process.close()
            }
        } finally {
            client.close()
        }
    }
}

private fun ptyCommand(command: String): ShellProcessCommand =
    ShellProcessCommand(command = command, shell = Shell(type = ShellType.Sh, path = Path("/bin/sh")), tty = true)
