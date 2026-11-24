@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.stream29.kodex.utils.shellclient

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.windows.CloseHandle
import platform.windows.OpenProcess
import platform.windows.SYNCHRONIZE
import platform.windows.WAIT_OBJECT_0
import platform.windows.WAIT_TIMEOUT
import platform.windows.WaitForSingleObject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

val windowsConPtyTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("ConPTY exit is published after final output and EOF") {
        val owner = Job()
        val client = CoroutineScope(currentCoroutineContext() + owner).ShellClient()
        try {
            val session = client.start(windowsTerminal(
                "[Console]::Write(('x' * 98304)); [Console]::WriteLine(); " +
                    "[Console]::WriteLine('kodex-conpty-final-tail'); exit 37",
            ))
            withTimeout(15.seconds) {
                assertEquals(37, session.exitCode.await())
                val merged = session.stdout.read()
                assertTrue("kodex-conpty-final-tail" in merged.renderedBytes().decodeToString())
                assertTrue("kodex-conpty-final-tail" in session.standardOutput.read().renderedBytes().decodeToString())
                assertTrue(session.standardError.read().isEmpty)
                session.scope.coroutineContext[Job]!!.join()
                assertTrue(session.stdout.read(offset = merged.nextOffset).isEmpty)
            }
        } finally {
            client.close()
            withContext(NonCancellable) { owner.cancelAndJoin() }
        }
    }

    test("ConPTY owner join terminates a live descendant and releases the session") {
        val owner = Job()
        val client = CoroutineScope(currentCoroutineContext() + owner).ShellClient()
        try {
            val session = client.start(windowsTerminal(
                "\$child = Start-Process -FilePath 'powershell.exe' " +
                    "-ArgumentList '-NoProfile','-Command','Start-Sleep -Seconds 60' -NoNewWindow -PassThru; " +
                    "[Console]::WriteLine('kodex-child=' + \$child.Id + ':ready'); Start-Sleep -Seconds 60",
            ))
            val pidPattern = Regex("kodex-child=(\\d+):ready")
            val pidText = withTimeout(10.seconds) {
                var match: MatchResult?
                do {
                    match = pidPattern.find(session.stdout.read().renderedBytes().decodeToString())
                    if (match == null) delay(20.milliseconds)
                } while (match == null)
                checkNotNull(match).groupValues[1]
            }
            val descendant = checkNotNull(OpenProcess(SYNCHRONIZE.toUInt(), 0, pidText.toUInt()))
            try {
                assertEquals(WAIT_TIMEOUT.toUInt(), WaitForSingleObject(descendant, 0u))
                withTimeout(10.seconds) { owner.cancelAndJoin() }
                // TerminateJobObject initiates termination. The descendant
                // itself becoming signalled independently verifies tree kill.
                val result = withContext(Dispatchers.IO) { WaitForSingleObject(descendant, 5_000u) }
                assertEquals(WAIT_OBJECT_0, result)
                assertTrue(session.exitCode.isCompleted)
                assertTrue(session.scope.coroutineContext[Job]!!.isCompleted)
                assertFailsWith<CancellationException> { session.stdout.read() }
            } finally {
                CloseHandle(descendant)
            }
        } finally {
            client.close()
            withContext(NonCancellable) { owner.cancelAndJoin() }
        }
    }
}

private fun windowsTerminal(command: String): ShellProcessCommand = ShellProcessCommand(
    command = command,
    shell = requireNotNull(Shell.resolve(ShellType.PowerShell)),
    tty = true,
)
