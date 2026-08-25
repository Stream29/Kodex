package io.github.stream29.kodex.utils.ktorclientext

import io.ktor.client.call.replaceResponse
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.client.request.ResponseAdapter
import io.ktor.client.request.ResponseAdapterAttributeKey
import io.ktor.client.statement.HttpReceivePipeline
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelinePhase
import io.ktor.utils.io.InternalAPI

/**
 * Supplies the SSE response content type when an explicitly marked server
 * response omits it.
 *
 * Ktor's official SSE plugin still owns request handling, response validation,
 * session lifecycle, and event parsing.
 */
@OptIn(InternalAPI::class)
public val SseCompatibility: ClientPlugin<Unit> = createClientPlugin("SseCompatibility") {
    client.requestPipeline.insertPhaseBefore(HttpRequestPipeline.Send, BeforeSseCompatibilitySend)
    client.requestPipeline.intercept(BeforeSseCompatibilitySend) {
        if (!context.attributes.contains(SseCompatibilityRequestAttribute)) {
            return@intercept
        }
        val adapter = context.attributes.getOrNull(ResponseAdapterAttributeKey)
            ?: return@intercept
        context.attributes.put(
            ResponseAdapterAttributeKey,
            ResponseAdapter { data, status, headers, responseBody, outgoingContent, callContext ->
                adapter.adapt(
                    data,
                    status,
                    headers.withSseContentType(status),
                    responseBody,
                    outgoingContent,
                    callContext,
                )
            },
        )
    }

    client.receivePipeline.intercept(HttpReceivePipeline.After) { response ->
        val request = response.call.request
        if (!request.attributes.contains(SseCompatibilityRequestAttribute)) {
            return@intercept
        }

        val headers = response.headers.withSseContentType(response.status)
        if (headers === response.headers) {
            return@intercept
        }
        proceedWith(response.call.replaceResponse(headers) { rawContent }.response)
    }
}

internal val SseCompatibilityRequestAttribute: AttributeKey<Unit> =
    AttributeKey("SseCompatibilityRequest")

private val BeforeSseCompatibilitySend: PipelinePhase =
    PipelinePhase("BeforeSseCompatibilitySend")

private fun Headers.withSseContentType(status: HttpStatusCode): Headers {
    if (status != HttpStatusCode.OK || this[HttpHeaders.ContentType] != null) {
        return this
    }
    return Headers.build {
        appendAll(this@withSseContentType)
        append(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
    }
}
