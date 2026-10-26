package io.github.stream29.kodex.rpc.control

import io.github.stream29.kodex.backend.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

public actual suspend fun backendRegisterBrowser(endpoint: BackendEndpoint, grant: LocalBrowserGrant): String {
    require(endpoint.businessProtocol == BackendBusinessProtocol)
    val status = backendControlRequest(endpoint, "status")
    check(status["phase"]?.jsonPrimitive?.content == "ready" &&
        status["instance"]?.jsonPrimitive?.content == endpoint.instanceId &&
        status["business_protocol"]?.jsonPrimitive?.content == BackendBusinessProtocol
    ) { "The selected backend is not ready or business-compatible." }
    check(status["browser_grant_protocol"]?.jsonPrimitive?.intOrNull == LocalBrowserGrantProtocol) {
        "The running backend lacks Native-authorized direct browser transport. No backend was restarted; explicitly use a matching backend build."
    }
    return requireNotNull(browserGrantRequest(endpoint, grant, "grant")["working_directory"]
        ?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }) { "Invalid browser admission directory." }
}

public actual suspend fun backendRevokeBrowser(endpoint: BackendEndpoint, grant: LocalBrowserGrant) {
    browserGrantRequest(endpoint, grant, "revoke")
}

private suspend fun browserGrantRequest(endpoint: BackendEndpoint, grant: LocalBrowserGrant, action: String): JsonObject {
    require(endpoint.controlProtocol == BackendControlProtocol && endpoint.businessProtocol == BackendBusinessProtocol)
    val client = HttpClient {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 10_000; connectTimeoutMillis = 5_000 }
    }
    return try {
        val response = client.post("http://127.0.0.1:${endpoint.port}/control/browser/$action") {
            header("Authorization", "Bearer ${endpoint.password}")
            header("X-Kodex-Instance", endpoint.instanceId)
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(LocalBrowserGrant.serializer(), grant))
        }
        check(response.status.value in 200..299) { "Backend browser admission rejected (${response.status.value})." }
        try { Json.parseToJsonElement(response.bodyAsText()).jsonObject }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error("Invalid browser admission response.") }
    } finally { client.close() }
}
