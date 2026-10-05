package io.github.stream29.kodex.utils.shellclient

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.io.files.Path
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

val shellContractFactoryTest by testSuite {
    test("scope factory returns the real spec and owns an independent child Job") {
        val owner = Job()
        val client: ShellClient = CoroutineScope(owner).ShellClient()
        try {
            val clientJob = requireNotNull(client.coroutineContext[Job])
            assertNotSame(owner, clientJob)
            assertTrue(clientJob in owner.children.toList())
            client.close()
            clientJob.join()
            assertTrue(owner.isActive)
            assertFailsWith<ProcessException> {
                client.start(
                    ShellProcessCommand("sentinel", shell = Shell(ShellType.Sh, Path("/not-started/sh"))),
                )
            }
        } finally {
            client.close()
            owner.cancelAndJoin()
        }
    }
}
