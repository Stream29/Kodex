package io.github.stream29.kodex.app.sessionrename

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameDependencies
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

val sessionRenameViewModelTest by testSuite {
    test("initial draft and edits do not dispatch, submission trims without replacing draft") {
        val names = mutableListOf<String>()
        val vm = createSessionRenameViewModel("initial", SessionRenameDependencies { names += it })
        try {
            assertEquals("initial", vm.draftName.value)
            vm.updateDraftName("  renamed  ")
            assertTrue(names.isEmpty())
            vm.rename()
            assertEquals(listOf("renamed"), names)
            assertEquals("  renamed  ", vm.draftName.value)
            assertTrue(vm.isActive)
        } finally {
            vm.close()
        }
    }
    test("dispatch stays bound to captured dependency and permits queued acknowledgment") {
        val source = mutableListOf<Pair<Long, String>>()
        val expectedRevision = 7L
        val vm = createSessionRenameViewModel(
            "captured",
            SessionRenameDependencies { name -> source += expectedRevision to name },
        )
        try {
            vm.rename()
            assertEquals(listOf(7L to "captured"), source)
        } finally {
            vm.close()
        }
    }
    test("operation failure propagates with the draft retained for retry") {
        val failure = IllegalArgumentException("operation failed")
        val vm = createSessionRenameViewModel("draft", SessionRenameDependencies { throw failure })
        try {
            assertSame(failure, assertFailsWith<IllegalArgumentException> { vm.rename() })
            assertEquals("draft", vm.draftName.value)
            assertTrue(vm.isActive)
        } finally {
            vm.close()
        }
    }
    test("cancellation is not translated into a completed operation") {
        val cancellation = CancellationException("cancelled")
        val vm = createSessionRenameViewModel("draft", SessionRenameDependencies { throw cancellation })
        try {
            assertSame(cancellation, assertFailsWith<CancellationException> { vm.rename() })
            assertTrue(vm.isActive)
        } finally {
            vm.close()
        }
    }
    test("close is idempotent, ignores late edits and rejects later operations") {
        var calls = 0
        val vm = createSessionRenameViewModel("draft", SessionRenameDependencies { calls++ })
        vm.close()
        vm.close()
        vm.updateDraftName("late")
        assertFalse(vm.isActive)
        assertEquals("draft", vm.draftName.value)
        assertFailsWith<IllegalStateException> { vm.rename() }
        assertEquals(0, calls)
    }
    test("closing during dispatch does not change the submitted snapshot or roll it back") {
        runTest {
            val started = CompletableDeferred<String>()
            val finish = CompletableDeferred<Unit>()
            val vm = createSessionRenameViewModel("first", SessionRenameDependencies {
                started.complete(it)
                finish.await()
            })
            val operation = launch { vm.rename() }
            assertEquals("first", started.await())
            vm.updateDraftName("next")
            vm.close()
            finish.complete(Unit)
            operation.join()
            assertFalse(operation.isCancelled)
            assertEquals("next", vm.draftName.value)
        }
    }
}
