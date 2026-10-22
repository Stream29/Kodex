package io.github.stream29.kodex.cli.agent

import io.github.stream29.kodex.app.agent.contract.HistoryIndexDependencies
import io.github.stream29.kodex.app.agent.contract.HistoryIndexViewModel
import io.github.stream29.kodex.app.agent.contract.HistoryIndexViewModelFactory
import kotlinx.coroutines.CoroutineScope

/** Actual dependency-only factory; no Agent/RPC/storage-owner access. */
public expect val historyIndexViewModelFactory: HistoryIndexViewModelFactory

public expect fun createHistoryIndexViewModel(
    dependencies: HistoryIndexDependencies,
    ownerScope: CoroutineScope,
): HistoryIndexViewModel
