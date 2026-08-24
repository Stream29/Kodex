/*
 * Derived from Ktor's Curl client engine.
 * Copyright 2014-2026 JetBrains s.r.o and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package io.github.stream29.kodex.utils.ktorclientext.kodexcurl

import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.util.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.*
import libcurl.curl_slist
import libcurl.curl_slist_free_all
import kotlin.coroutines.coroutineContext

@OptIn(ExperimentalForeignApi::class, InternalAPI::class)
internal suspend fun HttpRequestData.toKodexCurlRequest(
    config: KodexCurlEngineConfig,
    callContext: Job,
): KodexCurlRequestData {
    val content = body.toKodexCurlByteChannel()
    return KodexCurlRequestData(
        protocol = url.protocol.name,
        url = url.toString(),
        method = method.value,
        headers = headersToKodexCurl(),
        proxy = config.proxy,
        content = content,
        contentLength = body.contentLength ?: headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1L,
        connectTimeout = getCapabilityOrNull(HttpTimeoutCapability)?.connectTimeoutMillis,
        socketTimeout = getCapabilityOrNull(HttpTimeoutCapability)?.socketTimeoutMillis,
        callContext = callContext,
        isUpgradeRequest = isUpgradeRequest(),
        forceProxyTunneling = config.forceProxyTunneling,
        sslVerify = config.sslVerify,
        caInfo = config.caInfo,
        caPath = config.caPath,
        attributes = attributes,
    )
}

@OptIn(ExperimentalForeignApi::class)
internal class KodexCurlRequestData(
    val protocol: String,
    val url: String,
    val method: String,
    headers: CPointer<curl_slist>,
    val proxy: ProxyConfig?,
    val content: ByteReadChannel,
    val contentLength: Long,
    val connectTimeout: Long?,
    val socketTimeout: Long?,
    val callContext: Job,
    val isUpgradeRequest: Boolean,
    val forceProxyTunneling: Boolean,
    val sslVerify: Boolean,
    val caInfo: String?,
    val caPath: String?,
    val attributes: Attributes,
) {
    private var requestHeaders: CPointer<curl_slist>? = headers

    fun takeHeaders(): CPointer<curl_slist> = checkNotNull(requestHeaders).also {
        requestHeaders = null
    }

    fun dispose() {
        requestHeaders?.let(::curl_slist_free_all)
        requestHeaders = null
    }

    override fun toString(): String =
        "KodexCurlRequestData(url='$url', method='$method', content: $contentLength bytes)"
}

@Suppress("DEPRECATION")
internal class KodexCurlResponseBuilder(
    val request: KodexCurlRequestData,
    val bodyStartedReceiving: CompletableDeferred<Unit>,
    val responseBody: KodexCurlResponseBodyData,
) {
    val headersBytes: BytePacketBuilder = BytePacketBuilder()
}

internal sealed class KodexCurlResponseData

internal class KodexCurlSuccess(
    val status: Int,
    val version: Long,
    val headersBytes: ByteArray,
    val responseBody: KodexCurlResponseBodyData,
) : KodexCurlResponseData() {
    override fun toString(): String = "KodexCurlSuccess($status)"
}

internal class KodexCurlFail(
    val cause: Throwable,
) : KodexCurlResponseData() {
    override fun toString(): String = "KodexCurlFail($cause)"
}

@OptIn(DelicateCoroutinesApi::class)
internal suspend fun OutgoingContent.toKodexCurlByteChannel(): ByteReadChannel = when (this@toKodexCurlByteChannel) {
    is OutgoingContent.ByteArrayContent -> {
        val bytes = bytes()
        ByteReadChannel(bytes, 0, bytes.size)
    }

    is OutgoingContent.WriteChannelContent -> GlobalScope.writer(coroutineContext) {
        writeTo(channel)
    }.channel

    is OutgoingContent.ReadChannelContent -> readFrom()
    is OutgoingContent.NoContent -> ByteReadChannel.Empty
    is OutgoingContent.ContentWrapper -> delegate().toKodexCurlByteChannel()
    is OutgoingContent.ProtocolUpgrade -> throw UnsupportedContentTypeException(this@toKodexCurlByteChannel)
}
