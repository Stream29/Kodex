package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.app.test.*
import io.github.stream29.kodex.app.session.contract.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.io.files.Path

internal class SessionViewModelTestFixture private constructor(val rpc: RpcFrontendFixture) {
    private val singleton = rpc.draft()
    fun newSession(): NewSessionViewModel = singleton
    suspend fun persistedSession(name: String): PersistedSessionViewModel = rpc.create(name)
    suspend fun close() {
        singleton.close()
        rpc.closeAndJoin()
    }
    companion object {
        suspend fun create(
            scope: CoroutineScope,
            frontendDispatcher: CoroutineDispatcher? = null,
            seed: suspend CoroutineScope.(Path) -> Unit = {},
        ): SessionViewModelTestFixture =
            SessionViewModelTestFixture(startRpcFrontendFixture(scope, seed = seed, frontendDispatcher = frontendDispatcher))
    }
}
