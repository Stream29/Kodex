package io.github.stream29.kodex.utils.processclient

import kotlinx.coroutines.CoroutineScope

/**
 * Creates an independently cancellable direct-process client under this scope.
 * @throws IllegalArgumentException when this scope has no parent Job.
 */
public expect fun CoroutineScope.ProcessClient(): ProcessClient
