@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.stream29.kodex.rpc.browser

import io.github.stream29.kodex.backend.*
import io.github.stream29.kodex.rpc.local.FrontendRpcConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.engine.js.JsError
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.readByteArray

public actual suspend fun <R> withLocalFrontendClient(
    origin: String,
    path: String,
    ticket: String?,
    block: suspend CoroutineScope.(String, FrontendRpcConnection, FrontendHostConnection) -> R,
): R {
    validateLocalWebLocation(origin, path, ticket)
    require(origin == frontendPageOrigin()) { "Local frontend must use this page's origin." }
    val requestStartedMillis = frontendLeaseTimeMillis()
    val info = bootstrapLocalFrontend(origin, path, ticket)
    validateFrontendBootstrap(info, origin, origin)
    return withFrontendConnections(info, requestStartedMillis, block)
}

public actual suspend fun <R> withPairedFrontendClient(
    offer: LocalFrontendPairingOffer,
    block: suspend CoroutineScope.(String, FrontendRpcConnection, FrontendHostConnection) -> R,
): R {
    val origin = frontendPageOrigin()
    validatePairingOffer(offer, origin)
    val http = frontendHttp()
    val requestStartedMillis = frontendLeaseTimeMillis()
    val info = try {
        http.preparePost(offer.baseUrl + LocalFrontendPairingPath) {
            contentType(ContentType.Application.Json)
            setBody(LocalFrontendHostJson.encodeToString(
                LocalFrontendPairingRequest.serializer(),
                LocalFrontendPairingRequest(
                    LocalFrontendPairingProtocol, offer.ticket, origin, offer.workingDirectory,
                ),
            ))
        }.execute { response ->
            check(response.status.value in 200..299) { "Frontend pairing rejected." }
            decodeBootstrap(readFrontendFrame(response))
        }
    } finally { http.close() }
    validateFrontendBootstrap(info, origin, offer.baseUrl)
    check(info.host.hostInstanceId == offer.hostInstanceId &&
        info.endpoint.instanceId == offer.backendInstanceId &&
        info.workingDirectory == offer.workingDirectory
    ) { "Frontend pairing identity changed." }
    return withFrontendConnections(info, requestStartedMillis, block)
}

private fun frontendHttp(): HttpClient = HttpClient(Js) {
    followRedirects = false
    install(HttpTimeout) { requestTimeoutMillis = 10_000; connectTimeoutMillis = 5_000 }
}

internal fun decodeBootstrap(text: String): LocalFrontendBootstrap {
    check(text.encodeToByteArray().size <= LocalFrontendHostMaxFrameBytes) { "Frontend bootstrap is too large." }
    return try { LocalFrontendHostJson.decodeFromString(LocalFrontendBootstrap.serializer(), text) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Throwable) { error("Invalid frontend bootstrap response.") }
}

internal suspend fun readFrontendFrame(response: HttpResponse): String {
    val channel = response.bodyAsChannel()
    val bytes = try {
        channel.readRemaining(LocalFrontendHostMaxFrameBytes.toLong() + 1).use { it.readByteArray() }
    } finally { channel.cancel(null) }
    check(bytes.size <= LocalFrontendHostMaxFrameBytes) { "Frontend response is too large." }
    return try { bytes.decodeToString(throwOnInvalidSequence = true) }
    catch (_: Exception) { error("Invalid frontend response encoding.") }
}

private suspend fun <R> withFrontendConnections(
    info: LocalFrontendBootstrap,
    requestStartedMillis: Long,
    block: suspend CoroutineScope.(String, FrontendRpcConnection, FrontendHostConnection) -> R,
): R = coroutineScope {
    val http = frontendHttp()
    val descriptor = info.host
    val capabilityState = MutableStateFlow(descriptor.capabilities)
    val host = frontendHostConnection(descriptor, http, capabilityState)
    var renewals: Job? = null
    try {
        val lease = FrontendOwnerLease(requestStartedMillis, descriptor.leaseMillis, capabilityState)
        renewals = launch { lease.maintain(host::call) }
        withLocalBrowserClient(info.endpoint, info.workingDirectory) { directory, connection ->
            block(directory, connection, host)
        }
    } finally {
        withContext(NonCancellable) {
            renewals?.cancelAndJoin()
            try { withTimeout(3_000) { host.call(LocalFrontendHostRequest.CloseOwner) } }
            catch (_: Throwable) { /* Finite Native lease also cleans up a lost close reply. */ }
            http.close()
        }
    }
}

internal fun frontendHostConnection(
    descriptor: LocalFrontendHostDescriptor,
    http: HttpClient,
    capabilityState: StateFlow<FrontendHostCapabilities>,
): FrontendHostConnection {
    val bound = descriptor
    return object : FrontendHostConnection {
        override val descriptor = bound
        override val capabilities = capabilityState
        override suspend fun call(request: LocalFrontendHostRequest): LocalFrontendHostReply {
            val body = LocalFrontendHostJson.encodeToString(
                LocalFrontendHostCall.serializer(),
                LocalFrontendHostCall(
                    LocalFrontendHostProtocol, descriptor.hostInstanceId,
                    descriptor.credential, descriptor.owner, request,
                ),
            )
            require(body.encodeToByteArray().size <= LocalFrontendHostMaxFrameBytes) {
                "Frontend server request is too large."
            }
            val text = frontendTransport {
                http.preparePost(descriptor.baseUrl + LocalFrontendHostPath) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                    if (request is LocalFrontendHostRequest.AwaitCallback) {
                        timeout { requestTimeoutMillis = LocalFrontendHostCallbackWaitMillis + 10_000 }
                    }
                }.execute { response ->
                    if (response.status.value !in 200..299) {
                        response.bodyAsChannel().cancel(null)
                        if (response.status.value in 500..599) throw FrontendHostTransientFailure()
                        throw FrontendHostRejected()
                    }
                    readFrontendFrame(response)
                }
            }
            return try {
                val result = LocalFrontendHostJson.decodeFromString(LocalFrontendHostResult.serializer(), text)
                validateHostReply(request, validateHostResult(result, descriptor))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw FrontendHostInvalidResponse() }
        }
    }
}

private suspend fun <T> frontendTransport(block: suspend () -> T): T = try {
    block()
} catch (cancelled: CancellationException) { throw cancelled }
catch (failure: FrontendHostTransientFailure) { throw failure }
catch (failure: FrontendHostRejected) { throw failure }
catch (failure: Throwable) {
    currentCoroutineContext().ensureActive()
    // Do not use isBrowserNetworkFailure: it includes every Exception, including bad frames.
    val causes = generateSequence(failure) { it.cause }.take(8).toList()
    if (causes.any {
        (it is JsError && isFrontendFetchNetworkError(it.origin)) || it is HttpRequestTimeoutException ||
            it is ConnectTimeoutException || it is SocketTimeoutException
    }) throw FrontendHostTransientFailure()
    if (failure is Exception || causes.any { it is JsError }) throw FrontendHostInvalidResponse()
    throw failure
}

@JsFun("(error) => error != null && ['TypeError', 'NetworkError', 'AbortError'].includes(error.name)")
private external fun isFrontendFetchNetworkError(error: kotlin.js.JsAny): Boolean

@JsFun("() => location.origin")
private external fun frontendPageOrigin(): String
