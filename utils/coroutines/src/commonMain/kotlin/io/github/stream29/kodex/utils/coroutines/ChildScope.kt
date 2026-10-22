package io.github.stream29.kodex.utils.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.newCoroutineContext
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
public actual fun CoroutineScope.childScope(
    context: CoroutineContext,
): CoroutineScope {
    require(context[Job] == null) { "A child scope context must not contain a Job." }
    val parentJob = requireNotNull(coroutineContext[Job]) {
        "A child scope requires a parent CoroutineScope with a Job."
    }
    return CoroutineScope(newCoroutineContext(context + Job(parentJob)))
}

@OptIn(ExperimentalCoroutinesApi::class)
public actual fun CoroutineScope.supervisorChildScope(
    context: CoroutineContext,
): CoroutineScope {
    require(context[Job] == null) { "A child scope context must not contain a Job." }
    val parentJob = requireNotNull(coroutineContext[Job]) {
        "A child scope requires a parent CoroutineScope with a Job."
    }
    return CoroutineScope(newCoroutineContext(context + SupervisorJob(parentJob)))
}

public actual suspend fun CoroutineScope.cancelAndJoin() {
    coroutineContext.job.cancelAndJoin()
}
