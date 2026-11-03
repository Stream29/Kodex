package io.github.stream29.kodex.backend

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

val localFrontendHostProtocolTest by testSuite {
    val json = LocalFrontendHostJson

    test("all restricted host operations round trip with explicit correlation") {
        val reservation = LocalOAuthReservation("reservation-secret")
        val binding = LocalOAuthBinding(42, "state-secret", "https://provider.test/auth?state=state-secret")
        val requests = listOf(
            LocalFrontendHostRequest.ReserveCallback(LocalOAuthProvider.OpenAi, null),
            LocalFrontendHostRequest.ReserveCallback(LocalOAuthProvider.Mcp, "http://127.0.0.1:9000/callback"),
            LocalFrontendHostRequest.BindCallback(reservation, binding),
            LocalFrontendHostRequest.AwaitCallback(reservation),
            LocalFrontendHostRequest.OpenRegisteredAuthorization(reservation, 42),
            LocalFrontendHostRequest.CloseCallback(reservation),
            LocalFrontendHostRequest.ObserveError("safe fixture"),
            LocalFrontendHostRequest.ObserveError(null),
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
            assertFalse(call.toString().contains("secret"))
            assertFalse(request.toString().contains("secret"))
        }
    }

    test("bind ACK retains exact authorization fields and callback data stays out of toString") {
        val binding = LocalOAuthBinding(7, "exact-secret-state", "https://provider.test/?state=exact-secret-state")
        val reservation = LocalOAuthReservation("secret-reservation")
        val ack: LocalFrontendHostReply = LocalFrontendHostReply.CallbackBound(reservation, binding)
        val decoded = assertIs<LocalFrontendHostReply.CallbackBound>(
            json.decodeFromString<LocalFrontendHostReply>(json.encodeToString(ack)),
        )
        assertEquals(binding.attemptId, decoded.binding.attemptId)
        assertEquals(binding.state, decoded.binding.state)
        assertEquals(binding.authorizationUrl, decoded.binding.authorizationUrl)
        val received: LocalFrontendHostReply =
            LocalFrontendHostReply.CallbackReceived(reservation, 7, "http://localhost/callback?code=secret-code")
        val roundTrip = assertIs<LocalFrontendHostReply.CallbackReceived>(
            json.decodeFromString<LocalFrontendHostReply>(json.encodeToString(received)),
        )
        assertEquals("http://localhost/callback?code=secret-code", roundTrip.callbackUrl)
        for (value in listOf(binding, reservation, ack, decoded, received, roundTrip)) {
            assertFalse(value.toString().contains("secret"))
        }
    }

    test("typed replies preserve failures readiness and admission without success substitution") {
        val pending = FrontendHostCapabilities(
            FrontendCapabilityStatus.BackendOffline, FrontendCapabilityStatus.BackendOffline,
            FrontendCapabilityStatus.ServiceFailed, FrontendCapabilityStatus.Negotiating,
        )
        val replies = listOf(
            LocalFrontendHostReply.CallbackReserved(LocalOAuthReservation("r"), "http://localhost:1455/auth/callback"),
            LocalFrontendHostReply.AuthorizationOpened(LocalOAuthReservation("r"), 7),
            LocalFrontendHostReply.CallbackClosed(LocalOAuthReservation("r")),
            LocalFrontendHostReply.ErrorObserved(LocalErrorObservationAdmission.Dropped),
            LocalFrontendHostReply.OwnerRenewed(LocalFrontendHostOwnerLeaseMillis, pending),
            LocalFrontendHostReply.OwnerClosed,
            LocalFrontendHostReply.Failure(LocalFrontendHostFailure.BindingMismatch),
        )
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
                    """{"type":"observe_error","message":null,"$forbidden":"not-allowed"}""",
                )
            }
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<LocalFrontendHostRequest>("""{"type":"execute","command":"not-allowed"}""")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<LocalFrontendHostCall>("""{"credential":"secret"}""")
        }
    }
}
