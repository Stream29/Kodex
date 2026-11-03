package io.github.stream29.kodex.rpc.browser

import io.github.stream29.kodex.backend.*

internal fun validateFrontendBootstrap(
    info: LocalFrontendBootstrap,
    pageOrigin: String,
    hostOrigin: String,
) {
    require(pageOrigin == canonicalLocalBrowserOrigin(pageOrigin)) { "Invalid frontend origin." }
    require(hostOrigin == canonicalLocalBrowserOrigin(hostOrigin) &&
        hostOrigin.startsWith("http://127.0.0.1:")) { "Invalid companion endpoint." }
    val host = info.host
    require(info.protocol == LocalFrontendBootstrapProtocol &&
        info.workingDirectory.isNotBlank() &&
        host.protocol == LocalFrontendHostProtocol &&
        host.origin == pageOrigin && host.baseUrl == hostOrigin &&
        host.backendInstanceId == info.endpoint.instanceId &&
        host.hostInstanceId.length in 32..256 &&
        host.owner.value.length in 32..256 &&
        host.credential.length in 32..1024 &&
        host.credential != info.endpoint.password &&
        host.launchDirectory.isNotBlank() &&
        host.executionEnvironmentId.isNotBlank() &&
        host.leaseMillis == LocalFrontendHostOwnerLeaseMillis
    ) { "Frontend bootstrap is incompatible." }
}

internal fun validatePairingOffer(offer: LocalFrontendPairingOffer, pageOrigin: String) {
    require(pageOrigin == canonicalLocalBrowserOrigin(pageOrigin) &&
        offer.origin == pageOrigin &&
        offer.protocol == LocalFrontendPairingProtocol &&
        offer.baseUrl == canonicalLocalBrowserOrigin(offer.baseUrl) &&
        offer.baseUrl.startsWith("http://127.0.0.1:") &&
        offer.hostInstanceId.length in 32..256 &&
        offer.backendInstanceId.isNotBlank() &&
        offer.workingDirectory.isNotBlank() &&
        offer.ticket.length in 32..1024 &&
        offer.expiresInMillis in 1..LocalFrontendPairingTicketLifetimeMillis
    ) { "Invalid scoped frontend pairing offer." }
}

internal fun validateHostResult(
    result: LocalFrontendHostResult,
    host: LocalFrontendHostDescriptor,
): LocalFrontendHostReply {
    check(result.protocol == LocalFrontendHostProtocol &&
        result.hostInstanceId == host.hostInstanceId &&
        result.owner.value == host.owner.value
    ) { "Companion response does not belong to this frontend." }
    return result.reply
}
