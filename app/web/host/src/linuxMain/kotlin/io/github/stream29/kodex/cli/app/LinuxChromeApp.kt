@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.utils.kotlinxiocoroutines.readBytes
import io.github.stream29.kodex.utils.osenvironment.environmentVariable
import io.github.stream29.kodex.utils.processclient.ProcessClient
import io.github.stream29.kodex.utils.processclient.ProcessCommand
import io.github.stream29.kodex.utils.processclient.ProcessSession
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.posix.mkdtemp

internal actual val defaultLocalAppEngine: LocalAppEngine = LocalAppEngine.Chrome
internal actual val chromeAppSupported: Boolean = true

internal actual suspend fun runChromeApp(url: String, stopRequested: () -> Boolean): Int =
    runLinuxChromeApp(url, stopRequested)

/**
 * The POSIX process client requires setpgid before exec, so the fixed guard owns its group.
 * It stays alive until Chrome exits,
 * reports only Chrome's numeric status, then terminates that same group before it can be reused.
 * The URL is passed as an argument, never interpolated into shell code or written to diagnostics.
 */
internal suspend fun runLinuxChromeApp(
    url: String,
    stopRequested: () -> Boolean,
    executable: String = "google-chrome",
): Int {
    if (stopRequested()) return 130
    val owner = CoroutineScope(SupervisorJob(currentCoroutineContext()[Job]) + Dispatchers.Default)
    var client: ProcessClient? = null
    var profile: String? = null
    var process: ProcessSession? = null
    var failure: Throwable? = null
    try {
        val ownedClient = owner.ProcessClient()
        client = ownedClient
        val ownedProfile = privateChromeProfile()
        profile = ownedProfile
        val started = ownedClient.start(ProcessCommand(
            executable = "/bin/sh",
            arguments = listOf(
                "-c", chromeGuard, "kodex-chrome-guard", executable,
                "--user-data-dir=$ownedProfile", "--no-first-run", "--no-default-browser-check",
                "--disable-background-mode", "--ozone-platform-hint=auto", "--app=$url",
            ),
            environment = chromeSessionEnvironment(
                environmentVariable("WAYLAND_DISPLAY"), environmentVariable("DISPLAY"), environmentVariable("XDG_SESSION_TYPE"),
            ),
        ))
        process = started
        started.stdin.close()
        val status = owner.async {
            started.stdout.readBytes(64).decodeToString().trim().toIntOrNull()
                ?: error("Chrome application guard returned an invalid status.")
        }
        while (!status.isCompleted && !stopRequested()) delay(50)
        if (stopRequested()) return 130
        val chromeStatus = status.await()
        val guardStatus = started.exitCode.await()
        check(guardStatus == 137) { "Chrome application guard did not complete group cleanup." }
        check(chromeStatus == 0) {
            if (chromeStatus == 127) "Chrome executable was not found." else "Chrome application exited with status $chromeStatus."
        }
        return 0
    } catch (caught: Throwable) {
        failure = caught
        throw caught
    } finally {
        withContext(NonCancellable) {
            var cleanupFailure: Throwable? = null
            var groupClosed = process == null
            try {
                process?.closeAndJoin()
                groupClosed = true
            } catch (caught: Throwable) {
                cleanupFailure = caught
            }
            try { client?.close() }
            catch (caught: Throwable) {
                if (cleanupFailure == null) cleanupFailure = caught else cleanupFailure.addSuppressed(caught)
            }
            try { owner.coroutineContext.job.cancelAndJoin() }
            catch (caught: Throwable) {
                if (cleanupFailure == null) cleanupFailure = caught else cleanupFailure.addSuppressed(caught)
            }
            val ownedProfile = profile
            if (groupClosed && ownedProfile != null) {
                try { deletePrivateChromeProfile(ownedProfile) }
                catch (caught: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = caught else cleanupFailure.addSuppressed(caught)
                }
            }
            cleanupFailure?.let { caught ->
                val primary = failure
                if (primary == null) throw caught else primary.addSuppressed(caught)
            }
        }
    }
}

internal fun chromeSessionEnvironment(wayland: String?, display: String?, sessionType: String?): Map<String, String> =
    if (!wayland.isNullOrBlank() && display.isNullOrBlank() && sessionType.isNullOrBlank()) {
        mapOf("XDG_SESSION_TYPE" to "wayland")
    } else {
        emptyMap()
    }

private const val chromeGuard = """
"${'$'}@" >/dev/null 2>&1 &
child=${'$'}!
trap '' HUP INT TERM
wait "${'$'}child"
status=${'$'}?
printf '%s\n' "${'$'}status"
kill -TERM 0 2>/dev/null
sleep 1
kill -KILL 0
exit 125
"""

private fun privateChromeProfile(): String = memScoped {
    val template = "/tmp/kodex-app-chrome-XXXXXX".cstr.getPointer(this)
    (mkdtemp(template) ?: error("Cannot create a private Chrome profile.")).toKString()
}

private suspend fun deletePrivateChromeProfile(profile: String) {
    val cleanupOwner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val cleanupClient = cleanupOwner.ProcessClient()
    try {
        val cleanup = cleanupClient.start(ProcessCommand(
            executable = "/bin/sh",
            arguments = listOf("-c", "exec /bin/rm -rf -- \"\$1\" >/dev/null 2>&1", "kodex-profile-cleanup", profile),
        ))
        try {
            check(withTimeout(10_000) { cleanup.exitCode.await() } == 0) {
                "Cannot remove the private Chrome profile."
            }
        } finally {
            cleanup.closeAndJoin()
        }
    } finally {
        cleanupClient.close()
        cleanupOwner.coroutineContext.job.cancelAndJoin()
    }
}
