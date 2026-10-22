@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.rpc.models.CreatedSuggestedSession
import io.github.stream29.kodex.tool.multiagent.SuggestedSubagentTask
import kotlinx.io.files.Path

/** Management values from the same backend repository as live runtime services. */
public expect class BackendSessionManagement(
    host: BackendSessionHost,
    runtime: BackendAgentRuntimeRpc,
    newSessionDefaults: () -> KodexNewSessionSettings,
    reportBackgroundFailure: (Throwable) -> Unit,
) {
    public suspend fun getSessionCatalog(includeArchived: Boolean): List<SessionCatalogEntry>
    public suspend fun createSession(cwd: Path): Int
    public suspend fun keepSessionAlive(sessionIndex: Int)
    public suspend fun archiveSession(sessionIndex: Int)
    public suspend fun unarchiveSession(sessionIndex: Int)
    public suspend fun deleteSession(sessionIndex: Int): Boolean
    public suspend fun forkSession(sessionIndex: Int): Int
    public suspend fun forkSessionHistory(sessionIndex: Int, untilExclusive: Int, expectedCacheNonce: Long): Int
    public suspend fun createSuggestedSessions(
        tasks: List<SuggestedSubagentTask>,
        initialSettings: KodexAgentSettings,
    ): List<CreatedSuggestedSession>
}
