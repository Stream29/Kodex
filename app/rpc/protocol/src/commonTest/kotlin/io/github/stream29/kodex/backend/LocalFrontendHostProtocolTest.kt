package io.github.stream29.kodex.backend

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

val localFrontendHostProtocolTest by testSuite {
    val json = LocalFrontendHostJson

    test("all restricted host lease operations round trip with explicit correlation") {
        val requests = listOf(
            LocalFrontendHostRequest.RenewOwner,
            LocalFrontendHostRequest.CloseOwner,
        )
        for (request in requests) {
            val call = LocalFrontendHostCall(
                LocalFrontendHostProtocol, "host-test", "host-secret", LocalFrontendOwner("owner-secret"), request,
            )
            val encoded = json.encodeToString(call)
            val decoded = json.decodeFromString<LocalFrontendHostCall>(encoded)
            assertEquals(encoded, json.encodeToString(decoded))
            assertEquals(LocalFrontendHostProtocol, decoded.protocol)
            assertEquals("host-test", decoded.hostInstanceId)
            assertEquals("owner-secret", decoded.owner.value)
            assertFalse(call.toString().contains("secret"))
            assertFalse(decoded.owner.toString().contains("secret"))
        }
    }

    test("typed replies preserve failures and readiness without success substitution") {
        val pending = FrontendHostCapabilities(
            FrontendCapabilityStatus.BackendOffline,
            FrontendCapabilityStatus.ServiceFailed,
        )
        val replies = listOf(
            LocalFrontendHostReply.OwnerRenewed(LocalFrontendHostOwnerLeaseMillis, pending),
            LocalFrontendHostReply.OwnerClosed,
        ) + LocalFrontendHostFailure.entries.map { LocalFrontendHostReply.Failure(it) }
        for (reply in replies) {
            val result = LocalFrontendHostResult(LocalFrontendHostProtocol, "host", LocalFrontendOwner("owner"), reply)
            val encoded = json.encodeToString(result)
            assertEquals(encoded, json.encodeToString(json.decodeFromString<LocalFrontendHostResult>(encoded)))
        }
    }

    test("manual pairing has no management password and strict wire rejects arbitrary execution fields") {
        val pairing = LocalFrontendPairingRequest(
            LocalFrontendPairingProtocol, "secret-pair-ticket", "http://localhost:8000", """C:\launch""",
        )
        val encoded = json.encodeToString(pairing)
        val decoded = json.decodeFromString<LocalFrontendPairingRequest>(encoded)
        assertEquals(pairing.ticket, decoded.ticket)
        assertEquals(pairing.workingDirectory, decoded.workingDirectory)
        assertFalse(encoded.contains("password"))
        assertFalse(pairing.toString().contains("secret"))
        val offer = LocalFrontendPairingOffer(
            LocalFrontendPairingProtocol, "http://localhost:8001", "host", "backend",
            pairing.origin, pairing.workingDirectory, pairing.ticket, LocalFrontendPairingTicketLifetimeMillis,
        )
        val decodedOffer = json.decodeFromString<LocalFrontendPairingOffer>(json.encodeToString(offer))
        assertEquals(pairing.ticket, decodedOffer.ticket)
        assertFalse(offer.toString().contains("secret"))
        for (forbidden in listOf("command", "cwd", "env", "url")) {
            assertFailsWith<SerializationException> {
                json.decodeFromString<LocalFrontendHostRequest>(
                    """{"type":"renew_owner","$forbidden":"not-allowed"}""",
                )
            }
        }
        for (retired in listOf(
            "reserve_callback", "bind_callback", "await_callback",
            "open_registered_authorization", "close_callback", "observe_error", "execute",
        )) {
            assertFailsWith<SerializationException> {
                json.decodeFromString<LocalFrontendHostRequest>("""{"type":"$retired"}""")
            }
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<LocalFrontendHostCall>("""{"credential":"secret"}""")
        }
    }
}
