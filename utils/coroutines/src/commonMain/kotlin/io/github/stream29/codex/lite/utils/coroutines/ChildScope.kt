package io.github.stream29.codex.lite.utils.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.newCoroutineContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Creates a child scope whose child failure cancels its sibling coroutines.
 *
 * Parent cancellation propagates to the returned scope. Cancelling the
 * returned scope does not cancel this scope. [context] may replace inherited
 * context elements such as the dispatcher or coroutine name, but it must not
 * contain a [Job] because this function owns the parent-child relationship.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public fun CoroutineScope.childScope(
    context: CoroutineContext = EmptyCoroutineContext,
): CoroutineScope {
    require(context[Job] == null) { "A child scope context must not contain a Job." }
    val parentJob = requireNotNull(coroutineContext[Job]) {
        "A child scope requires a parent CoroutineScope with a Job."
    }
    return CoroutineScope(newCoroutineContext(context + Job(parentJob)))
}

/**
 * Creates a supervisor child scope whose child failures remain independent.
 *
 * Parent cancellation propagates to the returned scope. Cancelling the
 * returned scope does not cancel this scope. [context] may replace inherited
 * context elements such as the dispatcher or coroutine name, but it must not
 * contain a [Job] because this function owns the parent-child relationship.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public fun CoroutineScope.supervisorChildScope(
    context: CoroutineContext = EmptyCoroutineContext,
): CoroutineScope {
    require(context[Job] == null) { "A child scope context must not contain a Job." }
    val parentJob = requireNotNull(coroutineContext[Job]) {
        "A child scope requires a parent CoroutineScope with a Job."
    }
    return CoroutineScope(newCoroutineContext(context + SupervisorJob(parentJob)))
}

/** Cancels this scope's owned Job and waits for all of its children to finish. */
public suspend fun CoroutineScope.cancelAndJoin() {
    coroutineContext.job.cancelAndJoin()
}
