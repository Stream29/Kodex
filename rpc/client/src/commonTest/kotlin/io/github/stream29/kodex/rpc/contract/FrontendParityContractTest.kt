@file:OptIn(kotlinx.rpc.internal.utils.ExperimentalRpcApi::class)

package io.github.stream29.kodex.rpc.contract

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.rpc.descriptor.serviceDescriptorOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

val frontendParityContractTest by testSuite {
    test("approved parity methods have required typed query or exact attempt parameters") {
        val descriptor = serviceDescriptorOf<GlobalRpc>()
        for ((method, parameterName) in listOf(
            "validateDirectory" to "query",
            "listDirectories" to "query",
            "evaluateContextPaths" to "query",
            "abandonPendingOAuthLogin" to "attemptId",
        )) {
            val callable = assertNotNull(descriptor.getCallable(method))
            val parameter = callable.parameters.single()
            assertEquals(parameterName, parameter.name)
            assertFalse(parameter.isOptional)
        }
    }
}
