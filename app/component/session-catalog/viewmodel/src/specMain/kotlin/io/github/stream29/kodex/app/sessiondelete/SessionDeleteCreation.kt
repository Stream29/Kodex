package io.github.stream29.kodex.app.sessiondelete

import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteDependencies
import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteViewModel

public expect fun createSessionDeleteViewModel(
    sessionIndex: Int,
    name: String?,
    dependencies: SessionDeleteDependencies,
): SessionDeleteViewModel
