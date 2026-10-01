package io.github.stream29.kodex.cli.settings

import io.github.stream29.kodex.app.sessionrename.createSessionRenameViewModel
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameDependencies
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameViewModel
import io.github.stream29.kodex.app.settings.contract.SessionSettingsEffect
import io.github.stream29.kodex.app.settings.contract.SessionSettingsViewModel

/** Host glue only: the Settings command keeps its queued/revision-checked semantics. */
internal fun createSessionSettingsRenameChild(
    source: SessionSettingsViewModel,
    request: SessionSettingsEffect.RenameSession,
): SessionRenameViewModel = createSessionRenameViewModel(
    request.initialName,
    SessionRenameDependencies { name -> source.renameSession(request.expectedRevision, name) },
)
