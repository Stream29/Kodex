@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.cli.sessiontitle.SessionTitleGenerator
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import kotlinx.io.files.Path

/**
 * Management operations over [KodexSessionRepository].
 *
 * [createSession] automatically attaches one-shot session-title generation
 * (`attachSessionTitleGeneration`) onto the newly created Session.
 * [keepSessionAlive] delegates directly to [KodexSessionRepository.open], which activates
 * unloaded entries and renews residency TTL.
 */
public expect class BackendSessionManagement(
    repository: KodexSessionRepository,
    newSessionDefaults: () -> KodexNewSessionSettings,
    titleSettings: () -> SessionTitleSettings,
    titleGenerator: SessionTitleGenerator,
    reportBackgroundFailure: (Throwable) -> Unit,
    checkAdmission: () -> Unit = {},
) {
    public suspend fun getSessionCatalog(includeArchived: Boolean): List<SessionCatalogEntry>
    public suspend fun createSession(cwd: Path): Int
    public suspend fun keepSessionAlive(sessionIndex: Int)
    public suspend fun archiveSession(sessionIndex: Int)
    public suspend fun unarchiveSession(sessionIndex: Int)
    public suspend fun deleteSession(sessionIndex: Int): Boolean
    public suspend fun forkSession(sessionIndex: Int): Int
    public suspend fun forkSessionHistory(sessionIndex: Int, untilExclusive: Int, expectedCacheNonce: Long): Int
}
