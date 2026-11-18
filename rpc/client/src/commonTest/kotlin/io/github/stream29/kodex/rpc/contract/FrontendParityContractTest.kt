@file:OptIn(kotlinx.rpc.internal.utils.ExperimentalRpcApi::class)

package io.github.stream29.kodex.rpc.contract

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.rpc.descriptor.serviceDescriptorOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

val frontendParityContractTest by testSuite {
    test("read and close accept an optional exact shell registration without changing numeric addressing") {
        val descriptor = serviceDescriptorOf<AgentRuntimeRpc>()
        for (method in listOf("readShellSessionOutput", "closeShellSession")) {
            val callable = assertNotNull(descriptor.getCallable(method))
            assertEquals("shellSessionId", callable.parameters[1].name)
            assertFalse(callable.parameters[1].isOptional)
            assertEquals("expectedRegistrationNonce", callable.parameters.last().name)
            kotlin.test.assertTrue(callable.parameters.last().isOptional)
        }
    }

    test("approved parity methods have required typed query or exact attempt parameters") {
        val descriptor = serviceDescriptorOf<GlobalRpc>()
        for ((method, parameterName) in listOf(
            "validateDirectory" to "query",
            "listDirectories" to "query",
            "evaluateContextPaths" to "query",
            "startOAuthLogin" to "target",
            "cancelOAuthLogin" to "attemptId",
        )) {
            val callable = assertNotNull(descriptor.getCallable(method))
            val parameter = callable.parameters.single()
            assertEquals(parameterName, parameter.name)
            assertFalse(parameter.isOptional)
        }
        assertNull(descriptor.getCallable("awaitOAuthLogin"))
        assertNull(descriptor.getCallable("abandonPendingOAuthLogin"))
    }
}
