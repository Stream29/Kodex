package io.github.stream29.kodex.utils.coroutines

import kotlinx.coroutines.CancellationException

/**
 * Runs [block] and returns its value or captures its failure in a [Result].
 *
 * Any failure other than [CancellationException] is captured unchanged in
 * [Result.failure]. A [CancellationException] thrown by [block] is rethrown
 * unchanged instead of being captured.
 *
 * @throws CancellationException when [block] throws one.
 */
public expect inline fun <Value> runCatchingCancellable(block: () -> Value): Result<Value>
