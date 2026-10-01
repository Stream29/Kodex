package io.github.stream29.kodex.hook.contract.turn

/** Hook port owned by the outermost local-turn runtime. */
public interface TurnHooks {
    public suspend fun onUserPromptSubmit(request: UserPromptSubmitRequest): UserPromptSubmitResult

    public suspend fun onStop(request: StopRequest): StopResult
}

/** Turn-hook implementation that never changes control flow. */
public data object NoOpTurnHooks : TurnHooks {
    override suspend fun onUserPromptSubmit(request: UserPromptSubmitRequest): UserPromptSubmitResult =
        UserPromptSubmitResult.Continue()

    override suspend fun onStop(request: StopRequest): StopResult = StopResult.Finish
}
