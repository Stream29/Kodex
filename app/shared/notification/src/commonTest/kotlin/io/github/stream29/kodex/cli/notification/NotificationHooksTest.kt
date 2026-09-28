@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.cli.notification

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.rpc.models.*
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.shellclient.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Duration

private val event = Notification.Stop.UnhandledError(4, "literal $(touch unwanted)\n\"JSON\"")
private fun hook(name: String, command: String = name) = NotificationHook(
    name, setOf(NotificationHookType.StopUnhandledError, NotificationHookType.StopAssistantMessage), command,
)

val notificationHookControlTest by testSuite(testConfig = TestConfig.testScope(isEnabled = false)) {
    test("one match per multi-type hook preserves order and captures configuration once per event") {
        runTest {
            val settings = MutableStateFlow(CliFrontendSettings(hooks = listOf(hook("first"), hook("second"))))
            val commands = mutableListOf<ShellProcessCommand>()
            val inputs = mutableListOf<String>()
            collectNotificationHooks(flowOf(event, event), settings, Path("/frontend"), { command, input ->
                commands += command
                inputs += input
                settings.value = CliFrontendSettings(hooks = listOf(hook("next")))
            }) { _, failure -> throw failure }
            assertEquals(listOf("first", "second", "next"), commands.map { it.command })
            assertTrue(commands.all { it.workingDirectory == Path("/frontend") && !it.login && !it.tty && it.environment.isEmpty() })
            assertTrue(commands.all { it.shell == Shell.default })
            assertEquals(List(3) { Json.encodeToString<Notification>(event) }, inputs)
        }
    }
    test("unmatched hooks do not run and ordinary failures do not stop later commands") {
        runTest {
            val settings = MutableStateFlow(CliFrontendSettings(hooks = listOf(
                NotificationHook("skip", setOf(NotificationHookType.StopSuggestSubagent), "skip"),
                hook("bad"), hook("good"),
            )))
            val commands = mutableListOf<String>()
            val failures = mutableListOf<String>()
            collectNotificationHooks(flowOf(event), settings, Path("."), { command, _ ->
                commands += command.command
                if (command.command == "bad") error("failed")
            }) { name, _ -> failures += name }
            assertEquals(listOf("bad", "good"), commands)
            assertEquals(listOf("bad"), failures)
        }
    }
    test("outer cancellation propagates instead of draining the remaining hooks") {
        runTest {
            val settings = MutableStateFlow(CliFrontendSettings(hooks = listOf(hook("first"), hook("second"))))
            val entered = CompletableDeferred<Unit>()
            val seen = mutableListOf<String>()
            val work = launch {
                collectNotificationHooks(flowOf(event), settings, Path("."), { command, _ ->
                    seen += command.command
                    entered.complete(Unit)
                    awaitCancellation()
                }) { _, _ -> error("Cancellation is not a local diagnostic") }
            }
            entered.await()
            work.cancelAndJoin()
            assertEquals(listOf("first"), seen)
        }
    }
    test("the ten-second budget includes blocked stdin and waits for termination") {
        runTest {
            val process = FakeNotificationProcess(backgroundScope, bufferedInput = false)
            val operation = async {
                assertFailsWith<TimeoutCancellationException> {
                    executeNotificationProcess(ShellProcessCommand("test"), "input") { process }
                }
            }
            runCurrent()
            advanceTimeBy(9_999)
            assertEquals(0, process.closes)
            advanceTimeBy(1)
            runCurrent()
            operation.await()
            assertEquals(1, process.closes)
            assertTrue(process.exitCode.isCompleted)
        }
    }
    test("completed nonzero commands fail but still clean resources") {
        runTest {
            val process = FakeNotificationProcess(backgroundScope)
            process.exitCode.complete(17)
            assertFailsWith<IllegalStateException> {
                executeNotificationProcess(ShellProcessCommand("test"), "json") { process }
            }
            assertEquals("json", process.stdin.receive())
            assertTrue(process.stdin.receiveCatching().isClosed)
            assertEquals(1, process.closes)
        }
    }
    test("cleanup failure is suppressed behind the primary command failure") {
        runTest {
            val process = FakeNotificationProcess(backgroundScope)
            process.exitCode.complete(4)
            process.closeFailure = IllegalStateException("cleanup")
            val failed = assertFailsWith<IllegalStateException> {
                executeNotificationProcess(ShellProcessCommand("test"), "json") { process }
            }
            assertEquals("Notification command exited with code 4.", failed.message)
            assertEquals("cleanup", failed.suppressedExceptions.single().message)
        }
    }
    test("start failures do not attempt cleanup through an unacquired handle") {
        runTest {
            val failure = IllegalStateException("start")
            val actual = assertFailsWith<IllegalStateException> {
                executeNotificationProcess(ShellProcessCommand("test"), "json") { throw failure }
            }
            assertEquals(failure.message, actual.message)
        }
    }
    test("a command timeout is diagnosed and the next hook still runs") {
        runTest {
            val settings = MutableStateFlow(CliFrontendSettings(hooks = listOf(hook("slow"), hook("next"))))
            val seen = mutableListOf<String>()
            val failed = mutableListOf<String>()
            collectNotificationHooks(flowOf(event), settings, Path("."), { command, input ->
                seen += command.command
                if (command.command == "slow") {
                    executeNotificationProcess(command, input) { FakeNotificationProcess(backgroundScope, false) }
                }
            }) { name, _ -> failed += name }
            assertEquals(listOf("slow", "next"), seen)
            assertEquals(listOf("slow"), failed)
        }
    }
}

val notificationHookProcessTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("real command receives unchanged JSON on stdin in the frontend directory") {
        val root = Path(SystemTemporaryDirectory, "kodex-notification-${Random.nextLong()}")
        SystemCoroutineFileSystem.createDirectories(root)
        try {
            val settings = MutableStateFlow(CliFrontendSettings(hooks = listOf(hook("capture", "cat > received.json"))))
            collectNotificationHooks(flowOf(event), settings, root)
            assertEquals(
                Json.encodeToString<Notification>(event),
                SystemCoroutineFileSystem.readString(Path(root, "received.json")),
            )
            assertFalse(SystemCoroutineFileSystem.exists(Path(root, "unwanted")))
        } finally {
            SystemCoroutineFileSystem.list(root).forEach { SystemCoroutineFileSystem.delete(it) }
            SystemCoroutineFileSystem.delete(root)
        }
    }
    test("real process cancellation requests termination and observes its exit") {
        coroutineScope {
            val client = ShellClient()
            val entered = CompletableDeferred<ProcessSession>()
            try {
                val operation = launch {
                    executeNotificationProcess(ShellProcessCommand("read first; read second; read third"), "one\n") {
                        client.start(it).also { process -> entered.complete(process) }
                    }
                }
                val process = entered.await()
                operation.cancelAndJoin()
                assertTrue(process.exitCode.isCompleted)
            } finally {
                client.close()
                client.coroutineContext.job.join()
            }
        }
    }
}

private class FakeNotificationProcess(
    override val scope: CoroutineScope,
    bufferedInput: Boolean = true,
) : ProcessSession {
    override val stdin = Channel<String>(if (bufferedInput) 1 else 0)
    override val exitCode = CompletableDeferred<Int>()
    override val stdout: StdoutBuffer = object : StdoutBuffer {
        override suspend fun drain(): StdoutBufferSnapshot = error("Hook output must not be read as control data")
        override suspend fun read(yieldTime: Duration): StdoutBufferSnapshot = error("Hook output must not be read as control data")
    }
    override val standardOutput: StdoutBuffer get() = stdout
    override val standardError: StdoutBuffer get() = stdout
    var closes = 0
    var closeFailure: Throwable? = null
    override fun close() {
        closes++
        exitCode.complete(0)
        closeFailure?.let { throw it }
    }
}
