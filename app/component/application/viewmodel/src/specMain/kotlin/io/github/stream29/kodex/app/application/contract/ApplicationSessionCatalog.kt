package io.github.stream29.kodex.app.application.contract

import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionGroupReads

public interface ApplicationSessionCatalog : SessionGroupReads {
    public suspend fun isCurrentRoot(entry: SessionCatalogEntry): Boolean
    public suspend fun open(entry: SessionCatalogEntry): PersistedSessionViewModel
    public suspend fun rename(entry: SessionCatalogEntry, name: String)
}
