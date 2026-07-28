package io.github.stream29.codex.lite.utils.coroutines

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val childScopeTest by testSuite {
    test("parent cancellation cancels child scopes") {
        val parentJob = Job()
        val parent = CoroutineScope(currentCoroutineContext() + parentJob)
        val child = parent.childScope()
        val supervisorChild = parent.supervisorChildScope()

        parentJob.cancelAndJoin()

        assertFalse(child.coroutineContext[Job]!!.isActive)
        assertFalse(supervisorChild.coroutineContext[Job]!!.isActive)
    }

    test("regular child failure cancels siblings") {
        val owner = CoroutineScope(SupervisorJob())
        val parent = owner.childScope()
        val siblingCancelled = CompletableDeferred<Unit>()
        val sibling = parent.launch {
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                siblingCancelled.complete(Unit)
            }
        }
        val failed = parent.async { error("failed") }

        assertFailsWith<IllegalStateException> { failed.await() }
        siblingCancelled.await()

        assertFalse(sibling.isActive)
        owner.cancelAndJoin()
    }

    test("supervisor child failure leaves siblings active") {
        val owner = CoroutineScope(SupervisorJob())
        val parent = owner.supervisorChildScope()
        val failed = parent.async { error("failed") }
        val sibling = parent.launch { kotlinx.coroutines.awaitCancellation() }

        assertFailsWith<IllegalStateException> { failed.await() }
        yield()

        assertTrue(sibling.isActive)
        owner.cancelAndJoin()
    }

    test("rejects a context-owned job") {
        val parent = CoroutineScope(currentCoroutineContext())

        assertFailsWith<IllegalArgumentException> {
            parent.childScope(Job())
        }
        assertFailsWith<IllegalArgumentException> {
            parent.supervisorChildScope(Job())
        }
    }
}
