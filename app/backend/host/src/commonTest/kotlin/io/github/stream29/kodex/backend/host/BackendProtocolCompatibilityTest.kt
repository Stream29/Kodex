package io.github.stream29.kodex.backend.host

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.backend.BackendBusinessProtocol
import io.github.stream29.kodex.backend.BackendControlProtocol
import io.github.stream29.kodex.backend.BackendEndpoint
import io.github.stream29.kodex.backend.BackendInstance
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.control.backendControlRequest
import io.github.stream29.kodex.rpc.local.withLocalRpcClient
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlinx.rpc.withService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

val backendProtocolCompatibilityTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("old business checkpoints are refused without stopping or replacing the locked backend") {
        withHost { home, endpoint, backend ->
            assertEquals("0.7.8", endpoint.businessProtocol)
            assertEquals(1, endpoint.controlProtocol)
            val record = Path(home, ".locks", "backend", "connection.json")
            val originalRecord = SystemCoroutineFileSystem.readString(record)
            try {
                for (oldProtocol in listOf("0.7.4", "0.7.5", "0.7.7")) {
                    val obsolete = BackendEndpoint(
                        endpoint.instanceId, endpoint.pid, endpoint.port, endpoint.password,
                        endpoint.applicationVersion, businessProtocol = oldProtocol,
                    )
                    // Advertise an old checkpoint without changing the fixture process or its lock.
                    SystemCoroutineFileSystem.writeString(record, Json.encodeToString(BackendEndpoint.serializer(), obsolete))
                    assertEquals("ready", backendControlRequest(obsolete, "status")["phase"]?.jsonPrimitive?.content)
                    assertFailsWith<IllegalStateException> { ensureBackend(home) }
                    assertFailsWith<IllegalArgumentException> { withLocalRpcClient(home, obsolete) {} }
                    val stillLocked = assertNotNull(BackendInstance.discover(home))
                    assertEquals(endpoint.instanceId, stillLocked.instanceId)
                    assertEquals(endpoint.pid, stillLocked.pid)
                    assertEquals(oldProtocol, stillLocked.businessProtocol)
                    assertTrue(backend.isActive)
                    assertEquals("ready", backendControlRequest(obsolete, "status")["phase"]?.jsonPrimitive?.content)
                }
            } finally {
                SystemCoroutineFileSystem.writeString(record, originalRecord)
            }
            assertEquals(endpoint.instanceId, ensureBackend(home).instanceId)
            withLocalRpcClient(home, endpoint) { connection ->
                connection.withService<GlobalRpc>().getSettings()
            }
        }
    }

    test("new business checkpoint works across different application versions and management stays one") {
        withHost { home, endpoint, _ ->
            val differentBuild = BackendEndpoint(
                endpoint.instanceId, endpoint.pid, endpoint.port, endpoint.password,
                applicationVersion = "different-application-build",
                businessProtocol = BackendBusinessProtocol,
                controlProtocol = BackendControlProtocol,
            )
            assertEquals("ready", backendControlRequest(differentBuild, "status")["phase"]?.jsonPrimitive?.content)
            withLocalRpcClient(home, differentBuild) { connection ->
                connection.withService<GlobalRpc>().getSettings()
            }
        }
    }
}
