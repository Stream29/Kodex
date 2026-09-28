package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.app.test.*
import io.github.stream29.kodex.app.session.contract.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.files.Path

internal class SessionViewModelTestFixture private constructor(val rpc: RpcFrontendFixture) {
    private val drafts = mutableListOf<NewSessionViewModel>()
    fun newSession(name: String): NewSessionViewModel = rpc.draft(name).also(drafts::add)
    suspend fun persistedSession(name: String): PersistedSessionViewModel = rpc.create(name)
    suspend fun close() {
        drafts.forEach(NewSessionViewModel::close)
        rpc.closeAndJoin()
    }
    companion object {
        suspend fun create(scope: CoroutineScope, seed: suspend CoroutineScope.(Path) -> Unit = {}): SessionViewModelTestFixture =
            SessionViewModelTestFixture(startRpcFrontendFixture(scope, seed = seed))
    }
}
