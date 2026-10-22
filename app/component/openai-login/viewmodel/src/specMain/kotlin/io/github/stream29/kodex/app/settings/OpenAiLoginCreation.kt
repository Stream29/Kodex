package io.github.stream29.kodex.app.settings

import io.github.stream29.kodex.app.settings.contract.OpenAiLoginDependencies
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginViewModel
import io.github.stream29.kodex.cli.auth.KodexAuthLoginAttempt
import kotlinx.coroutines.CoroutineScope

/** Uses the same browser/revision UI with a frontend-owned RPC login attempt. */
public expect fun createOpenAiLoginViewModel(
    ownerScope: CoroutineScope,
    startLogin: suspend () -> KodexAuthLoginAttempt,
): OpenAiLoginViewModel

/** Constructs the component using its declared dependency port and child lifecycle. */
public expect fun createOpenAiLoginViewModel(
    dependencies: OpenAiLoginDependencies,
    ownerScope: CoroutineScope,
): OpenAiLoginViewModel
