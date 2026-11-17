package io.github.stream29.kodex.app.settings

import io.github.stream29.kodex.app.settings.contract.OpenAiLoginDependencies
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginViewModel
import kotlinx.coroutines.CoroutineScope

/** Constructs the component using its declared dependency port and child lifecycle. */
public expect fun createOpenAiLoginViewModel(
    dependencies: OpenAiLoginDependencies,
    ownerScope: CoroutineScope,
): OpenAiLoginViewModel
