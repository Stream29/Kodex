@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.control

import io.github.stream29.kodex.backend.BackendBusinessProtocol
import io.github.stream29.kodex.backend.BackendControlProtocol
import io.github.stream29.kodex.backend.BackendEndpoint
import io.github.stream29.kodex.backend.LocalBrowserGrant
import io.github.stream29.kodex.backend.LocalBrowserGrantProtocol
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public actual class BackendControlClient actual constructor() : AutoCloseable {
    private val client = HttpClient {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 10_000; connectTimeoutMillis = 5_000 }
    }

    public actual suspend fun request(endpoint: BackendEndpoint, action: String): JsonObject {
        require(endpoint.controlProtocol == BackendControlProtocol) { "Backend control protocol is incompatible." }
        require(action in ControlActions)
        val url = "http://127.0.0.1:${endpoint.port}/control/$action"
        val response = if (action == "status") client.get(url) {
            header("Authorization", "Bearer ${endpoint.password}")
            header("X-Kodex-Instance", endpoint.instanceId)
        } else client.post(url) {
            header("Authorization", "Bearer ${endpoint.password}")
            header("X-Kodex-Instance", endpoint.instanceId)
        }
        check(response.status.value in 200..299) { "Backend rejected '$action' (${response.status.value})." }
        return Json.parseToJsonElement(response.bodyAsText()) as JsonObject
    }

    public actual suspend fun registerBrowser(endpoint: BackendEndpoint, grant: LocalBrowserGrant): String {
        require(endpoint.businessProtocol == BackendBusinessProtocol)
        val status = request(endpoint, "status")
        check(
            status["phase"]?.jsonPrimitive?.content == "ready" &&
                status["instance"]?.jsonPrimitive?.content == endpoint.instanceId &&
                status["business_protocol"]?.jsonPrimitive?.content == BackendBusinessProtocol,
        ) { "The selected backend is not ready or business-compatible." }
        check(status["browser_grant_protocol"]?.jsonPrimitive?.intOrNull == LocalBrowserGrantProtocol) {
            "The running backend lacks Native-authorized direct browser transport. No backend was restarted; explicitly use a matching backend build."
        }
        return requireNotNull(
            browserGrantRequest(endpoint, grant, "grant")["working_directory"]
                ?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
        ) { "Invalid browser admission directory." }
    }

    public actual suspend fun revokeBrowser(endpoint: BackendEndpoint, grant: LocalBrowserGrant) {
        browserGrantRequest(endpoint, grant, "revoke")
    }

    private suspend fun browserGrantRequest(
        endpoint: BackendEndpoint,
        grant: LocalBrowserGrant,
        action: String,
    ): JsonObject {
        require(endpoint.controlProtocol == BackendControlProtocol && endpoint.businessProtocol == BackendBusinessProtocol)
        val response = client.post("http://127.0.0.1:${endpoint.port}/control/browser/$action") {
            header("Authorization", "Bearer ${endpoint.password}")
            header("X-Kodex-Instance", endpoint.instanceId)
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(LocalBrowserGrant.serializer(), grant))
        }
        check(response.status.value in 200..299) { "Backend browser admission rejected (${response.status.value})." }
        return try {
            val body = response.bodyAsText()
            // Avoid the thread-local Json.Default on resumption in optimized Kotlin/Native builds.
            Json {}.parseToJsonElement(body).jsonObject
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error("Invalid browser admission response.")
        }
    }

    actual override fun close() {
        client.close()
    }
}

private val ControlActions = setOf("status", "stop", "reload")

public actual suspend fun backendControlRequest(endpoint: BackendEndpoint, action: String): JsonObject =
    BackendControlClient().use { it.request(endpoint, action) }
