package io.github.stream29.kodex.utils.processclient

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

val processContractFactoryTest by testSuite {
    test("scope factory returns the real spec and owns an independent child Job") {
        val owner = Job()
        val client: ProcessClient = CoroutineScope(owner).ProcessClient()
        try {
            val clientJob = requireNotNull(client.coroutineContext[Job])
            assertNotSame(owner, clientJob)
            assertTrue(clientJob in owner.children.toList())
            client.close()
            clientJob.join()
            assertTrue(owner.isActive)
            assertFailsWith<ProcessException> {
                client.start(ProcessCommand("/not-started/sentinel"))
            }
        } finally {
            client.close()
            owner.cancelAndJoin()
        }
    }
}
