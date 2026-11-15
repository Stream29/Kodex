@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.tool.unifiedexec

import io.github.stream29.kodex.utils.shellclient.ShellClient
import io.github.stream29.kodex.utils.shellclient.ShellSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path

/** Environment variable injected into `exec_command` subprocesses carrying the owning Agent ID. */
public const val KodexAgentIdEnvironmentVariable: String = "KODEX_AGENT_ID"

/** Environment variable injected into `exec_command` subprocesses carrying the active Kodex Home path. */
public const val KodexHomeEnvironmentVariable: String = "KODEX_HOME"

/**
 * ShellClient-backed implementation of [UnifiedExecClient].
 *
 * This client owns one [ShellClient], which owns every process session it
 * creates. The registry and process-session synchronization remain local to
 * this implementation.
 */
public expect class UnifiedExecToolClient : UnifiedExecClient {
    public override val activeSessions: StateFlow<Map<Int, UnifiedExecProcessSession>>
    public override suspend fun execCommand(arguments: ExecCommandArguments): UnifiedExecOutput
    public override fun close()
}

/**
 * Creates a unified-exec client with a dedicated shell client under this scope.
 *
 * [environmentProvider] supplies additional environment variables (such as
 * [KodexAgentIdEnvironmentVariable] and [KodexHomeEnvironmentVariable]) merged
 * onto every spawned shell session.
 */
public expect fun CoroutineScope.UnifiedExecToolClient(
    settingsProvider: suspend () -> ShellSettings,
    workingDirectoryProvider: suspend () -> Path = { Path(".") },
    environmentProvider: suspend () -> Map<String, String> = { emptyMap() },
): UnifiedExecToolClient
