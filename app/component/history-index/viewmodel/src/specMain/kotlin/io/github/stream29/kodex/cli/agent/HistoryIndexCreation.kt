package io.github.stream29.kodex.cli.agent

import io.github.stream29.kodex.app.agent.contract.HistoryIndexDependencies
import io.github.stream29.kodex.app.agent.contract.HistoryIndexViewModel
import kotlinx.coroutines.CoroutineScope

public expect fun createHistoryIndexViewModel(
    dependencies: HistoryIndexDependencies,
    ownerScope: CoroutineScope,
): HistoryIndexViewModel
