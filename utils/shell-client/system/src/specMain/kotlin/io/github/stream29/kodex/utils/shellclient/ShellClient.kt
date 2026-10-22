package io.github.stream29.kodex.utils.shellclient

import kotlinx.coroutines.CoroutineScope

/**
 * Creates an independently cancellable shell client under this scope.
 * @throws IllegalArgumentException when this scope has no parent Job.
 */
public expect fun CoroutineScope.ShellClient(): ShellClient
