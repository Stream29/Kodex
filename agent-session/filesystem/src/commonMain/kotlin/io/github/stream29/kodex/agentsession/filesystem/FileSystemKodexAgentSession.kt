package io.github.stream29.kodex.agentsession.filesystem

import io.github.stream29.kodex.agentruntime.contract.AgentRuntime
import io.github.stream29.kodex.agentruntime.impl.buildMasterAgentRuntime
import io.github.stream29.kodex.agentsession.contract.KodexAgentDependencies
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentstate.contract.KodexAgentState as KodexAgentStateContract
import io.github.stream29.kodex.agentstate.impl.KodexAgentState
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.filesystem.FileSystemAgentStorage
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.filesystemlease.FileSystemLease
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

internal class FileSystemKodexAgentSession(
    override val storage: MutableKodexAgentStorage,
    scope: CoroutineScope,
    override val runtime: AgentRuntime,
) : KodexAgentSession, CoroutineScope by scope

internal suspend fun CoroutineScope.FileSystemKodexAgentSession(
    directory: Path,
    fileSystem: CoroutineFileSystem,
    valueCacheSize: Int,
    dependencies: KodexAgentDependencies,
): KodexAgentSession {
    val scope = supervisorChildScope()
    val lease = try {
        scope.FileSystemLease(
            lockPath = Path(directory, LockFile),
            fileSystem = fileSystem,
            duration = SessionLeaseDuration,
        )
    } catch (failure: Throwable) {
        scope.cancelAndJoin()
        throw failure
    }
    try {
        val storage = FileSystemAgentStorage(directory, fileSystem).cached(scope, valueCacheSize)
        val state: KodexAgentStateContract = scope.KodexAgentState(
            client = dependencies.client,
            storage = storage,
            contextSettings = dependencies.contextSettings,
            mcpService = dependencies.mcpService,
        )
        val session = FileSystemKodexAgentSession(
            storage = storage,
            scope = scope,
            runtime = state.buildMasterAgentRuntime(dependencies),
        )
        scope.coroutineContext[Job]?.invokeOnCompletion { lease.close() }
        return session
    } catch (failure: Throwable) {
        withContext(NonCancellable) {
            scope.cancelAndJoin()
            lease.close()
        }
        throw failure
    }
}
