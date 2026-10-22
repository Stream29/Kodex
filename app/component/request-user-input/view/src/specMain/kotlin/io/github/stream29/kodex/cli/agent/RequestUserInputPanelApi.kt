package io.github.stream29.kodex.cli.agent

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.agent.contract.RequestUserInputState
import io.github.stream29.kodex.app.agent.contract.RequestUserInputViewModel

/** Renders the exact pending child snapshot without taking ownership of its lifecycle. */
@Composable
public expect fun RequestUserInputPanel(
    viewModel: RequestUserInputViewModel,
    state: RequestUserInputState.Pending,
    columns: Int,
    rows: Int,
): Unit
