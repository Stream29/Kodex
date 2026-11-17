package io.github.stream29.kodex.app.sessionrename

import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameDependencies
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameViewModel

public expect fun createSessionRenameViewModel(
    initialName: String,
    dependencies: SessionRenameDependencies,
): SessionRenameViewModel
