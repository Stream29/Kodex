package io.github.stream29.kodex.utils.shellclient

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

val stdinChannelTest by testSuite {
    test("waits for the pending platform write to complete") {
        val ownerJob = SupervisorJob()
        val ownerScope = CoroutineScope(ownerJob)
        val stdin = StdinChannel(ownerScope, ownerJob)
        try {
            coroutineScope {
                val sent = async(start = CoroutineStart.UNDISPATCHED) {
                    stdin.send("text")
                }
                assertFalse(sent.isCompleted)

                val pending = stdin.receive()
                assertEquals("text", pending.text)
                assertFalse(sent.isCompleted)

                pending.written.complete(Unit)
                sent.await()
            }
        } finally {
            ownerScope.cancel()
        }
    }

    test("cancelling the owner scope rejects a waiting send") {
        val ownerJob = SupervisorJob()
        val ownerScope = CoroutineScope(ownerJob)
        val stdin = StdinChannel(ownerScope, ownerJob)
        try {
            coroutineScope {
                val sent = async(start = CoroutineStart.UNDISPATCHED) {
                    stdin.send("text")
                }
                assertFalse(sent.isCompleted)

                ownerScope.cancel()

                assertFailsWith<CancellationException> {
                    sent.await()
                }
            }
        } finally {
            ownerScope.cancel()
        }
    }

    test("normal close preserves a claimed platform write") {
        val ownerJob = SupervisorJob()
        val ownerScope = CoroutineScope(ownerJob)
        val stdin = StdinChannel(ownerScope, ownerJob)
        try {
            coroutineScope {
                val sent = async(start = CoroutineStart.UNDISPATCHED) {
                    stdin.send("text")
                }
                val pending = stdin.receive()

                assertEquals(true, stdin.close())
                assertFalse(sent.isCompleted)

                pending.written.complete(Unit)
                sent.await()
            }
        } finally {
            ownerScope.cancel()
        }
    }

    test("cancelling a claimed platform write settles its sender") {
        val ownerJob = SupervisorJob()
        val ownerScope = CoroutineScope(ownerJob)
        val stdin = StdinChannel(ownerScope, ownerJob)
        try {
            coroutineScope {
                val sent = async(start = CoroutineStart.UNDISPATCHED) {
                    stdin.send("text")
                }
                stdin.receive()

                stdin.cancel()

                assertFailsWith<CancellationException> {
                    sent.await()
                }
            }
        } finally {
            ownerScope.cancel()
        }
    }
}
