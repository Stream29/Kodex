package io.github.stream29.kodex.rpc.control

import io.github.stream29.kodex.backend.BackendControlProtocol
import io.github.stream29.kodex.backend.BackendEndpoint
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

public actual suspend fun backendControlRequest(endpoint: BackendEndpoint, action: String): JsonObject {
    require(endpoint.controlProtocol == BackendControlProtocol) { "Backend control protocol is incompatible." }
    require(action in setOf("status", "stop", "reload"))
    val client = HttpClient {
        install(HttpTimeout) { requestTimeoutMillis = 10_000; connectTimeoutMillis = 5_000 }
    }
    try {
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
    } finally { client.close() }
}
