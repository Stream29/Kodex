package io.github.stream29.kodex.agentruntime.steer

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.openai.ContentItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val stateFlowSteerProviderTest by testSuite {
    test("already queued input wakes without consuming and retains duplicates") {
        runTest {
            val input = input("same")
            val queue = queue(input, input)
            val provider = StateFlowSteerProvider(queue)

            provider.waitForSteer()

            assertEquals(listOf(input, input), queue.value)
            assertEquals(listOf(input, input), provider.take())
            assertTrue(provider.take().isEmpty())
        }
    }

    test("empty queue suspends until an append without consuming it") {
        runTest {
            val queue = queue()
            val provider = StateFlowSteerProvider(queue)
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { provider.waitForSteer() }
            assertFalse(waiter.isCompleted)

            val input = input("next")
            queue.update { it + input }
            waiter.await()

            assertEquals(listOf(input), queue.value)
        }
    }

    test("cancelling an empty wait leaves the queue unchanged") {
        runTest {
            val queue = queue()
            val provider = StateFlowSteerProvider(queue)
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { provider.waitForSteer() }

            waiter.cancelAndJoin()

            assertFailsWith<CancellationException> { waiter.await() }
            assertTrue(queue.value.isEmpty())
            queue.value = listOf(input("later"))
            provider.waitForSteer()
            assertEquals(listOf(input("later")), queue.value)
        }
    }

    test("cancellation after append does not retract queued input") {
        runTest {
            val queue = queue()
            val provider = StateFlowSteerProvider(queue)
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { provider.waitForSteer() }

            queue.value = listOf(input("retained"))
            waiter.cancelAndJoin()

            assertEquals(listOf(input("retained")), queue.value)
        }
    }

    test("a completed wait does not reserve input against retraction") {
        runTest {
            val input = input("retract")
            val queue = queue(input)
            val provider = StateFlowSteerProvider(queue)

            provider.waitForSteer()
            assertEquals(listOf(input), queue.getAndUpdate { emptyList() })

            assertTrue(provider.take().isEmpty())
        }
    }

    test("multiple waiters observe one queue but only one claimant owns a batch") {
        runTest {
            val queue = queue()
            val provider = StateFlowSteerProvider(queue)
            val firstWait = async(start = CoroutineStart.UNDISPATCHED) { provider.waitForSteer() }
            val secondWait = async(start = CoroutineStart.UNDISPATCHED) { provider.waitForSteer() }
            val input = input("one")

            queue.value = listOf(input)
            firstWait.await()
            secondWait.await()
            assertEquals(listOf(input), queue.value)

            val runtimeClaim = async { provider.take() }
            val competingClaim = async { queue.getAndUpdate { emptyList() } }
            val claims = listOf(runtimeClaim.await(), competingClaim.await())
            assertEquals(listOf(listOf(input)), claims.filter { it.isNotEmpty() })
            assertTrue(queue.value.isEmpty())
        }
    }

    test("a cancelled claimant does not remove pending input") {
        runTest {
            val input = input("keep")
            val queue = queue(input)
            val provider = StateFlowSteerProvider(queue)
            val claim = async {
                currentCoroutineContext().cancel()
                provider.take()
            }

            assertFailsWith<CancellationException> { claim.await() }
            assertEquals(listOf(input), queue.value)
        }
    }
}

private fun queue(vararg input: StableIndexEvent.Steerable): MutableStateFlow<List<StableIndexEvent.Steerable>> =
    MutableStateFlow(input.toList())

private fun input(text: String): StableUserMessage =
    StableUserMessage(listOf(ContentItem.InputText(text)))
