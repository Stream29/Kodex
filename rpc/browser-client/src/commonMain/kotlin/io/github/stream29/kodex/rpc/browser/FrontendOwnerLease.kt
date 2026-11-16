package io.github.stream29.kodex.rpc.browser

import io.github.stream29.kodex.backend.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.TimeSource

private val frontendLeaseClock = TimeSource.Monotonic.markNow()
internal fun frontendLeaseTimeMillis(): Long = frontendLeaseClock.elapsedNow().inWholeMilliseconds

internal class FrontendHostTransientFailure : IllegalStateException("Frontend companion transport unavailable.")
internal class FrontendHostRejected : IllegalStateException("Frontend companion request rejected.")
internal class FrontendHostInvalidResponse : IllegalStateException("Invalid frontend companion response.")

internal class FrontendOwnerLease(
    requestStartedMillis: Long,
    leaseMillis: Long,
    private val capabilities: MutableStateFlow<FrontendHostCapabilities>,
    private val nowMillis: () -> Long = ::frontendLeaseTimeMillis,
) {
    private var confirmedDeadline = requestStartedMillis + leaseMillis
    private var nextAttempt = requestStartedMillis + LocalFrontendHostRenewIntervalMillis

    init {
        check(leaseMillis == LocalFrontendHostOwnerLeaseMillis) { "Invalid frontend companion lease." }
        if (capabilities.value.hasEndedOwner()) throw FrontendHostRejected()
        remaining()
    }

    private fun remaining(): Long = (confirmedDeadline - nowMillis()).also {
        check(it > 0) { "Frontend companion lease ended." }
    }

    // Only this loop retries, and only RenewOwner. A lost reply never confirms a new deadline.
    suspend fun maintain(call: suspend (LocalFrontendHostRequest) -> LocalFrontendHostReply): Nothing {
        while (true) {
            delay(minOf((nextAttempt - nowMillis()).coerceAtLeast(0), remaining()))
            currentCoroutineContext().ensureActive()
            val budget = minOf(10_000L, remaining())
            val started = nowMillis()
            val reply = try {
                withTimeoutOrNull(budget) { call(LocalFrontendHostRequest.RenewOwner) }
                    ?: throw FrontendHostTransientFailure()
            } catch (_: FrontendHostTransientFailure) {
                remaining()
                nextAttempt = nowMillis() + 1_000
                continue
            }
            // Even a valid late reply must not revive an owner after the last confirmed lease.
            remaining()
            if (reply !is LocalFrontendHostReply.OwnerRenewed ||
                reply.leaseMillis != LocalFrontendHostOwnerLeaseMillis ||
                reply.capabilities.hasEndedOwner()
            ) throw FrontendHostRejected()
            confirmedDeadline = started + reply.leaseMillis
            remaining()
            capabilities.value = reply.capabilities
            nextAttempt = started + LocalFrontendHostRenewIntervalMillis
        }
    }
}

private fun FrontendHostCapabilities.hasEndedOwner(): Boolean =
    directoryBrowsing == FrontendCapabilityStatus.OwnerClosed ||
        directoryBrowsing == FrontendCapabilityStatus.BackendOffline ||
        contextPathEvaluation == FrontendCapabilityStatus.OwnerClosed ||
        contextPathEvaluation == FrontendCapabilityStatus.BackendOffline

internal fun validateHostReply(
    request: LocalFrontendHostRequest,
    reply: LocalFrontendHostReply,
): LocalFrontendHostReply {
    if (reply is LocalFrontendHostReply.Failure) return reply
    val matches = when (request) {
        LocalFrontendHostRequest.RenewOwner -> reply is LocalFrontendHostReply.OwnerRenewed
        LocalFrontendHostRequest.CloseOwner -> reply is LocalFrontendHostReply.OwnerClosed
    }
    if (!matches) throw FrontendHostInvalidResponse()
    return reply
}
