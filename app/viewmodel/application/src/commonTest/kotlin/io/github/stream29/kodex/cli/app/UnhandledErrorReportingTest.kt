package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.agent.contract.AgentHistoryTarget
import io.github.stream29.kodex.cli.settings.KodexGlobalSettings
import io.github.stream29.kodex.cli.settings.openGlobalSettings
import io.github.stream29.kodex.hook.contract.HookBody
import io.github.stream29.kodex.hook.contract.HookType
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellType
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

val unhandledErrorReportingTest by testSuite(
    compartment = { TestCompartment.RealTime },
) {
    test("real application reports operation failures once with captured cwd and owns notification lifetime") {
        val root = Path(
            SystemTemporaryDirectory,
            "kodex-error-reporting-${Random.nextLong()}",
        ).let {
            SystemCoroutineFileSystem.createDirectories(it)
            SystemCoroutineFileSystem.resolve(it)
        }
        val home = Path(root, "home")
        val startup = Path(root, "startup")
        val sourceCwd = Path(root, "source")
        val messageFile = Path(root, "message")
        val cwdFile = Path(root, "cwd")
        val countFile = Path(root, "count")
        val promptStarted = Path(root, "prompt-started")
        val releasePrompt = Path(root, "release-prompt")
        val promptCompleted = Path(root, "prompt-completed")
        var application: KodexApplication? = null
        try {
            listOf(home, startup, sourceCwd).forEach { SystemCoroutineFileSystem.createDirectories(it) }
            openGlobalSettings(home, KodexGlobalSettings()).update {
                it.copy(
                    sessionTitle = it.sessionTitle.copy(enabled = false),
                    hooks = mapOf(
                        "record-error" to HookBody(
                            HookType.UnhandledError,
                            recordApplicationError(messageFile, cwdFile, countFile),
                        ),
                        "failed-notification" to HookBody(HookType.UnhandledError, failingNotificationCommand()),
                        "pause-prompt" to HookBody(
                            HookType.UserPromptSubmit,
                            pausePromptCommand(promptStarted, releasePrompt, promptCompleted),
                        ),
                    ),
                )
            }
            val app = KodexApplication.openWithCodexDirectory(
                codexDirectory = Path(root, "unused-codex"),
                agentsDirectory = Path(root, "unused-agents"),
                workingDirectory = startup,
                dataDirectory = home,
            )
            application = app

            suspend fun assertReported(failure: Throwable, cwd: Path, count: Int) {
                withTimeout(5.seconds) {
                    while (
                        !SystemCoroutineFileSystem.exists(countFile) ||
                        SystemCoroutineFileSystem.readString(countFile).length < count
                    ) {
                        delay(10.milliseconds)
                    }
                }
                assertEquals("x".repeat(count), SystemCoroutineFileSystem.readString(countFile))
                assertEquals(failure.message.orEmpty(), SystemCoroutineFileSystem.readString(messageFile))
                assertEquals(
                    SystemCoroutineFileSystem.resolve(cwd).toString(),
                    SystemCoroutineFileSystem.readString(cwdFile).trim(),
                )
            }

            val openFailure = assertFailsWith<IllegalArgumentException> { app.viewModel.openSession(123) }
            assertReported(openFailure, startup, 1)
            val unopenedFork = assertFailsWith<IllegalArgumentException> { app.viewModel.forkSession(124) }
            assertReported(unopenedFork, startup, 2)

            val session = app.viewModel.materializeNewSession(0)
            session.updateWorkingDirectory(sourceCwd)
            val revertFailure = assertFailsWith<IllegalArgumentException> {
                session.rootAgent.requestHistoryRevert(AgentHistoryTarget(999, 999))
            }
            assertReported(revertFailure, sourceCwd, 3)

            // A real filesystem collision fails inside createFork, before its old try/catch.
            val obstacle = Path(home, "sessions/${session.sessionIndex + 1}")
            SystemCoroutineFileSystem.writeString(obstacle, "not a directory")
            val forkFailure = try {
                assertFailsWith<IOException> { session.fork() }
            } finally {
                SystemCoroutineFileSystem.delete(obstacle)
            }
            assertTrue(app.viewModel.closeTab(session))
            // The notification belongs to the application, not the closed Session.
            assertReported(forkFailure, sourceCwd, 4)

            val runningSession = app.viewModel.materializeNewSession(0)
            runningSession.updateWorkingDirectory(sourceCwd)
            runningSession.rootAgent.submit(listOf(ContentItem.InputText("test missing authentication")))
            withTimeout(5.seconds) {
                while (!SystemCoroutineFileSystem.exists(promptStarted)) delay(10.milliseconds)
            }
            runningSession.updateWorkingDirectory(startup)
            SystemCoroutineFileSystem.writeString(releasePrompt, "")
            withTimeout(5.seconds) {
                while (SystemCoroutineFileSystem.readString(countFile).length < 5) delay(10.milliseconds)
            }
            assertEquals("xxxxx", SystemCoroutineFileSystem.readString(countFile))
            assertTrue(SystemCoroutineFileSystem.readString(messageFile).isNotEmpty())
            assertEquals(
                SystemCoroutineFileSystem.resolve(sourceCwd).toString(),
                SystemCoroutineFileSystem.readString(cwdFile).trim(),
            )

            // Fork can succeed while the catalog refresh fails independently.
            val invalidEntry = Path(home, "sessions/not-a-session")
            SystemCoroutineFileSystem.createDirectories(invalidEntry)
            val catalogFailure = try {
                val catalog = app.viewModel.openSessionCatalogPopup()
                assertFailsWith<IllegalArgumentException> { catalog.viewModel.fork(runningSession.sessionIndex) }
            } finally {
                SystemCoroutineFileSystem.delete(invalidEntry)
            }
            assertTrue(catalogFailure.message.orEmpty().contains("not-a-session"))
            assertReported(catalogFailure, startup, 6)

            listOf(promptStarted, releasePrompt, promptCompleted).forEach { SystemCoroutineFileSystem.delete(it) }
            app.viewModel.createNewSessionTab()
            val cancelledSession = app.viewModel.materializeNewSession(app.viewModel.navigation.value.selectedIndex)
            cancelledSession.rootAgent.submit(listOf(ContentItem.InputText("cancel this operation")))
            withTimeout(5.seconds) {
                while (!SystemCoroutineFileSystem.exists(promptStarted)) delay(10.milliseconds)
            }
            withTimeout(5.seconds) { app.shutdown() }
            application = null
            SystemCoroutineFileSystem.writeString(releasePrompt, "")
            delay(100.milliseconds)
            assertEquals("xxxxxx", SystemCoroutineFileSystem.readString(countFile))
            assertTrue(!SystemCoroutineFileSystem.exists(promptCompleted))
        } finally {
            application?.shutdown()
            deleteErrorTestDirectory(root)
        }
    }
}

private fun recordApplicationError(message: Path, cwd: Path, count: Path): String {
    val powershell = "[IO.File]::WriteAllText('${message.psQuoted()}', [Console]::In.ReadToEnd()); " +
        "[IO.File]::WriteAllText('${cwd.psQuoted()}', (Get-Location).Path); " +
        "[IO.File]::AppendAllText('${count.psQuoted()}', 'x')"
    return when (Shell.default.type) {
        ShellType.Sh, ShellType.Bash, ShellType.Zsh ->
            "cat > '${message.shQuoted()}'; pwd > '${cwd.shQuoted()}'; printf x >> '${count.shQuoted()}'"

        ShellType.PowerShell -> powershell
        ShellType.Cmd -> "powershell -NoProfile -NonInteractive -Command \"$powershell\""
    }
}

private fun pausePromptCommand(started: Path, release: Path, completed: Path): String {
    val powershell = "\$null = [Console]::In.ReadToEnd(); " +
        "[IO.File]::WriteAllText('${started.psQuoted()}', 'started'); " +
        "while (!(Test-Path '${release.psQuoted()}')) { Start-Sleep -Milliseconds 10 }; " +
        "[IO.File]::WriteAllText('${completed.psQuoted()}', 'completed')"
    return when (Shell.default.type) {
        ShellType.Sh, ShellType.Bash, ShellType.Zsh ->
            "cat >/dev/null; printf started > '${started.shQuoted()}'; " +
                "while [ ! -f '${release.shQuoted()}' ]; do sleep 0.01; done; " +
                "printf completed > '${completed.shQuoted()}'"

        ShellType.PowerShell -> powershell
        ShellType.Cmd -> "powershell -NoProfile -NonInteractive -Command \"$powershell\""
    }
}

private fun Path.shQuoted(): String = toString().replace("'", "'\"'\"'")
private fun Path.psQuoted(): String = toString().replace("'", "''")

private fun failingNotificationCommand(): String = when (Shell.default.type) {
    ShellType.Sh, ShellType.Bash, ShellType.Zsh -> "cat >/dev/null; exit 7"
    ShellType.PowerShell -> "\$null = [Console]::In.ReadToEnd(); exit 7"
    ShellType.Cmd -> "more > nul & exit 7"
}

private suspend fun deleteErrorTestDirectory(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { deleteErrorTestDirectory(it) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
