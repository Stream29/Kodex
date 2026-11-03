package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.backend.*
import io.github.stream29.kodex.rpc.control.backendRegisterBrowser
import io.github.stream29.kodex.rpc.control.backendRevokeBrowser
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlin.time.TimeSource

internal fun frontendAssetPath(path: String): Boolean = path.isNotEmpty() &&
    path.split('/').all { it.matches(Regex("[A-Za-z0-9_@.-]+")) && it != "." && it != ".." }

public actual suspend fun <R> withLocalFrontendHost(
    endpoint: BackendEndpoint,
    directory: String,
    openResource: (String) -> Source?,
    newSecret: () -> String,
    block: suspend CoroutineScope.(privateLaunchUrl: String) -> R,
): R = withFrontendHostListener(endpoint, directory, openResource, newSecret) { url, _ -> block(url) }

public actual suspend fun <R> withLocalFrontendPairingHost(
    endpoint: BackendEndpoint,
    directory: String,
    origin: String,
    newSecret: () -> String,
    block: suspend CoroutineScope.(LocalFrontendPairingOffer) -> R,
): R = withFrontendHostListener(
    endpoint, directory, { null }, newSecret, canonicalLocalBrowserOrigin(origin),
) { _, offer -> block(requireNotNull(offer)) }

/** Native-only composition seam; public entry points always acquire the actual local adapters. */
internal suspend fun <R> withFrontendHostListener(
    endpoint: BackendEndpoint,
    directory: String,
    openResource: (String) -> Source?,
    newSecret: () -> String,
    manualOrigin: String? = null,
    acquireResources: suspend (CoroutineScope, LocalFrontendOwner) -> FrontendCompanionResources =
        { scope, _ -> acquireNativeFrontendCompanion(scope) },
    block: suspend CoroutineScope.(String, LocalFrontendPairingOffer?) -> R,
): R = coroutineScope {
    // Native window event loops block the caller thread; owner jobs still belong to this host.
    val hostScope = CoroutineScope(coroutineContext + Dispatchers.Default)
    val id = newSecret()
    val capability = newSecret()
    val probeCredential = newSecret()
    require(listOf(id, capability, probeCredential).all { it.matches(Regex("[0-9a-f]{64}")) })
    require(setOf(id, capability, probeCredential, endpoint.password).size == 4)
    if (manualOrigin == null) {
        check(openResource("index.html")?.use { true } == true) { "Embedded website unavailable." }
    } else require(manualOrigin == canonicalLocalBrowserOrigin(manualOrigin))
    val clock = TimeSource.Monotonic.markNow()
    fun now() = clock.elapsedNow().inWholeMilliseconds
    val owners = MutableStateFlow<FrontendCompanionOwners?>(null)
    val canonical = MutableStateFlow<String?>(null)
    val offerMutex = Mutex()
    var offerExpiresAt = 0L
    var offerConsumed = false
    val engine = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
        fun ApplicationCall.origin() = "http://127.0.0.1:${request.local.localPort}"
        fun ApplicationCall.exactHost(): Boolean =
            request.headers.getAll(HttpHeaders.Host)?.singleOrNull() == "127.0.0.1:${request.local.localPort}"
        fun ApplicationCall.acceptsOrigin(required: Boolean, paired: Boolean = false): Boolean {
            if (!exactHost()) return false
            val origins = request.headers.getAll(HttpHeaders.Origin)
            val expected = if (paired) manualOrigin ?: origin() else origin()
            if (origins == null && required || origins != null && origins.singleOrNull() != expected) return false
            if (!(paired && manualOrigin != null) && request.headers["Sec-Fetch-Site"] == "cross-site") return false
            return true
        }
        fun ApplicationCall.headers(paired: Boolean = false) {
            response.header(HttpHeaders.CacheControl, "no-store")
            response.header("X-Content-Type-Options", "nosniff")
            response.header("Referrer-Policy", "no-referrer")
            response.header("X-Frame-Options", "DENY")
            response.header("Content-Security-Policy", "frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
            if (paired && manualOrigin != null && acceptsOrigin(required = true, paired = true)) {
                response.header(HttpHeaders.AccessControlAllowOrigin, manualOrigin)
                response.header(HttpHeaders.Vary, HttpHeaders.Origin)
            }
        }
        fun ApplicationCall.acceptsJson(): Boolean {
            val value = request.headers.getAll(HttpHeaders.ContentType)?.singleOrNull() ?: return false
            return try { parseHeaderValue(value).size == 1 && ContentType.parse(value).match(ContentType.Application.Json) }
            catch (_: BadContentTypeFormatException) { false }
        }
        suspend fun ApplicationCall.boundedBody(limit: Int): String? {
            val bytes = receiveChannel().readRemaining(limit.toLong() + 1).use { it.readByteArray() }
            return if (bytes.size > limit) null else try { bytes.decodeToString(throwOnInvalidSequence = true) }
            catch (_: Exception) { null }
        }
        suspend fun ApplicationCall.newOwner(origin: String): LocalFrontendBootstrap? {
            val registry = owners.value ?: return null
            val directory = canonical.value ?: return null
            return try { withTimeoutOrNull(15_000) { registry.open(origin, directory) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
        routing {
            post("/web/$id/bootstrap") {
                call.headers()
                if (manualOrigin != null || !call.acceptsOrigin(true) || !call.acceptsJson()) {
                    call.respondText("Frontend bootstrap rejected.", status = HttpStatusCode.Forbidden)
                    return@post
                }
                val ticket = try {
                    call.boundedBody(1_024)?.let { LocalFrontendHostJson.decodeFromString(LocalWebTicket.serializer(), it) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                if (ticket?.ticket != capability) {
                    call.respondText("Frontend capability required.", status = HttpStatusCode.Unauthorized)
                    return@post
                }
                val info = call.newOwner(call.origin())
                if (info == null) call.respondText("Frontend bootstrap unavailable.", status = HttpStatusCode.ServiceUnavailable)
                else call.respondText(
                    LocalFrontendHostJson.encodeToString(LocalFrontendBootstrap.serializer(), info),
                    ContentType.Application.Json,
                )
            }
            for (path in listOf(LocalFrontendPairingPath, LocalFrontendHostPath)) {
                options(path) {
                    call.headers(paired = true)
                    val requestedHeaders = call.request.headers[HttpHeaders.AccessControlRequestHeaders]
                        ?.split(',')?.map { it.trim().lowercase() }.orEmpty()
                    if (manualOrigin == null || !call.acceptsOrigin(true, true) ||
                        call.request.headers.getAll(HttpHeaders.AccessControlRequestMethod)?.singleOrNull() != "POST" ||
                        requestedHeaders.any { it != "content-type" }
                    ) {
                        call.respondText("Frontend preflight rejected.", status = HttpStatusCode.Forbidden)
                    } else {
                        call.response.header(HttpHeaders.AccessControlAllowMethods, "POST")
                        call.response.header(HttpHeaders.AccessControlAllowHeaders, "Content-Type")
                        call.respondText("", status = HttpStatusCode.NoContent)
                    }
                }
            }
            post(LocalFrontendPairingPath) {
                call.headers(paired = true)
                if (manualOrigin == null || !call.acceptsOrigin(true, true) || !call.acceptsJson()) {
                    call.respondText("Frontend pairing rejected.", status = HttpStatusCode.Forbidden)
                    return@post
                }
                val request = try {
                    call.boundedBody(LocalFrontendHostMaxFrameBytes)?.let {
                        LocalFrontendHostJson.decodeFromString(LocalFrontendPairingRequest.serializer(), it)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                val accepted = offerMutex.withLock {
                    if (request == null || offerConsumed || now() >= offerExpiresAt ||
                        request.protocol != LocalFrontendPairingProtocol || request.ticket != capability ||
                        request.origin != manualOrigin || request.workingDirectory != canonical.value
                    ) false else { offerConsumed = true; true }
                }
                if (!accepted) {
                    call.respondText("Scoped pairing capability required.", status = HttpStatusCode.Unauthorized)
                    return@post
                }
                val info = call.newOwner(manualOrigin)
                if (info == null) call.respondText("Frontend pairing unavailable.", status = HttpStatusCode.ServiceUnavailable)
                else call.respondText(
                    LocalFrontendHostJson.encodeToString(LocalFrontendBootstrap.serializer(), info),
                    ContentType.Application.Json,
                )
            }
            post(LocalFrontendHostPath) {
                call.headers(paired = true)
                if (!call.acceptsOrigin(true, true) || !call.acceptsJson()) {
                    call.respondText("Frontend server request rejected.", status = HttpStatusCode.Forbidden)
                    return@post
                }
                val request = try {
                    call.boundedBody(LocalFrontendHostMaxFrameBytes)?.let {
                        LocalFrontendHostJson.decodeFromString(LocalFrontendHostCall.serializer(), it)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                if (request == null) {
                    call.respondText("Invalid frontend server request.", status = HttpStatusCode.BadRequest)
                    return@post
                }
                val result = try {
                    owners.value?.call(manualOrigin ?: call.origin(), request)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    call.respondText("Frontend server unavailable.", status = HttpStatusCode.ServiceUnavailable)
                    return@post
                }
                if (result == null) {
                    call.respondText("Frontend server owner unavailable.", status = HttpStatusCode.Unauthorized)
                    return@post
                }
                val text = LocalFrontendHostJson.encodeToString(LocalFrontendHostResult.serializer(), result)
                if (text.encodeToByteArray().size > LocalFrontendHostMaxFrameBytes) {
                    call.respondText("Frontend server response limit exceeded.", status = HttpStatusCode.InternalServerError)
                } else call.respondText(text, ContentType.Application.Json)
            }
            get("/web/$id/{path...}") {
                call.headers()
                val path = call.parameters.getAll("path")?.joinToString("/").orEmpty().ifEmpty { "index.html" }
                if (manualOrigin != null || !call.acceptsOrigin(false) || !frontendAssetPath(path)) {
                    call.respondText("Not found.", status = HttpStatusCode.NotFound)
                    return@get
                }
                val resource = openResource(path)
                if (resource == null) {
                    call.respondText("Not found.", status = HttpStatusCode.NotFound)
                    return@get
                }
                val type = when (path.substringAfterLast('.')) {
                    "html" -> ContentType.Text.Html
                    "js", "mjs" -> ContentType.Application.JavaScript
                    "wasm" -> ContentType("application", "wasm")
                    "css" -> ContentType.Text.CSS
                    "json" -> ContentType.Application.Json
                    "ttf" -> ContentType("font", "ttf")
                    "woff" -> ContentType("font", "woff")
                    "woff2" -> ContentType("font", "woff2")
                    "txt", "md" -> ContentType.Text.Plain
                    else -> ContentType.Application.OctetStream
                }
                resource.use { call.respondSource(it, type) }
            }
        }
    }
    var probe: LocalBrowserGrant? = null
    var reaper: Job? = null
    var primary: Throwable? = null
    try {
        engine.startSuspend()
        val port = engine.engine.resolvedConnectors().single().port
        val origin = "http://127.0.0.1:$port"
        check(port != endpoint.port)
        val request = LocalBrowserGrant(LocalBrowserGrantProtocol, manualOrigin ?: origin, probeCredential, directory)
        probe = request
        canonical.value = backendRegisterBrowser(endpoint, request)
        owners.value = FrontendCompanionOwners(
            hostScope, endpoint, id, origin, directory, id, newSecret, ::now,
            backendOnline = { frontendBackendReady(endpoint) },
            register = { backendRegisterBrowser(endpoint, it) },
            revoke = { grant ->
                try { backendRevokeBrowser(endpoint, grant) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    // Local resources have already joined. An ended exact backend has no live
                    // admission to revoke; unknown replies stay bounded by its finite grant.
                    if (frontendBackendReady(endpoint)) throw failure
                }
            },
            acquire = acquireResources,
            reservedSecrets = setOf(id, capability, probeCredential),
        )
        reaper = hostScope.launch {
            while (isActive) { delay(LocalFrontendHostRenewIntervalMillis); owners.value?.expire() }
        }
        offerMutex.withLock { offerExpiresAt = now() + LocalFrontendPairingTicketLifetimeMillis }
        val offer = manualOrigin?.let {
            LocalFrontendPairingOffer(
                LocalFrontendPairingProtocol, origin, id, endpoint.instanceId, it,
                requireNotNull(canonical.value), capability, LocalFrontendPairingTicketLifetimeMillis,
            )
        }
        block("$origin/web/$id/#$capability", offer)
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        withContext(NonCancellable) {
            suspend fun cleanup(action: suspend () -> Unit) {
                try { action() }
                catch (failure: Throwable) {
                    if (primary == null) primary = failure else primary.addSuppressed(failure)
                }
            }
            cleanup { reaper?.cancelAndJoin() }
            cleanup { owners.value?.closeAndJoin() }
            owners.value = null
            canonical.value = null
            cleanup { engine.stopSuspend() }
            probe?.let {
                try { withTimeout(3_000) { backendRevokeBrowser(endpoint, it) } }
                catch (_: Exception) { /* Same-instance only; the finite grant also expires. */ }
            }
        }
        if (primary != null && currentCoroutineContext().isActive) throw requireNotNull(primary)
    }
}
